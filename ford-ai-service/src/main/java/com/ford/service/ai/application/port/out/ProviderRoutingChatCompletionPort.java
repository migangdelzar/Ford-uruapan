package com.ford.service.ai.application.port.out;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AiProviderFailureDiagnostics;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ModelRequirements;
import com.ford.service.ai.application.ProviderFailure;
import com.ford.service.ai.application.ProviderRetryDelay;
import com.ford.service.ai.application.ThreadBasedProviderRetryDelay;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Duration;

/** Applies the application provider policy to the concrete Spring AI adapters. */
public final class ProviderRoutingChatCompletionPort implements ChatCompletionPort {

    private final Map<String, ProviderChatCompletionPort> ports;
    private final ModelProviderPolicy policy;
    private final int maxAttempts;
    private final Duration maxRetryAfter;
    private final ProviderRetryDelay retryDelay;
    private final AiObservationService observations;

    public ProviderRoutingChatCompletionPort(List<? extends ProviderChatCompletionPort> ports,
                                             ModelProviderPolicy policy) {
        this(ports, policy, 2, Duration.ofSeconds(3), new ThreadBasedProviderRetryDelay(),
                new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public ProviderRoutingChatCompletionPort(List<? extends ProviderChatCompletionPort> ports,
                                             ModelProviderPolicy policy,
                                             int maxAttempts,
                                             Duration maxRetryAfter,
                                             ProviderRetryDelay retryDelay) {
        this(ports, policy, maxAttempts, maxRetryAfter, retryDelay,
                new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    public ProviderRoutingChatCompletionPort(List<? extends ProviderChatCompletionPort> ports,
                                             ModelProviderPolicy policy,
                                             int maxAttempts,
                                             Duration maxRetryAfter,
                                             ProviderRetryDelay retryDelay,
                                             AiObservationService observations) {
        Objects.requireNonNull(ports, "ports must not be null");
        this.ports = ports.stream().collect(Collectors.toUnmodifiableMap(
                ProviderChatCompletionPort::providerId, Function.identity(), (left, right) -> left));
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("At least one provider attempt is required");
        }
        this.maxAttempts = maxAttempts;
        this.maxRetryAfter = Objects.requireNonNull(maxRetryAfter, "maxRetryAfter must not be null");
        if (maxRetryAfter.isNegative()) {
            throw new IllegalArgumentException("maxRetryAfter must not be negative");
        }
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        if (this.ports.isEmpty()) {
            throw new IllegalArgumentException("At least one provider port is required");
        }
    }

    @Override
    public AssistantResponse complete(AssistantRequest request, ToolContext context) {
        Objects.requireNonNull(request, "request must not be null");
        var candidates = policy.candidates(
                new AiExecutionContext(request.tenantId(), request.containsSensitiveData(), request.conversationId(),
                        request.memoryContext(), request.decision()),
                new ModelRequirements(true, true));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No provider satisfies the request policy");
        }
        RuntimeException lastFailure = null;
        var calls = 0;
        for (var candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            if (calls >= maxAttempts) break;
            var candidate = candidates.get(candidateIndex);
            var port = ports.get(candidate.id());
            if (port == null) {
                throw new IllegalStateException("Selected AI provider is not configured: " + candidate.id());
            }
            calls++;
            try {
                var response = port.complete(request, context);
                observations.event("ford.ai.provider.attempt", Map.of(
                        "provider", candidate.id(), "outcome", "success"));
                return response;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                var classified = AiProviderFailureDiagnostics.classify(failure);
                if (!isRetryable(classified)) {
                    observations.event("ford.ai.provider.attempt", Map.of(
                            "provider", candidate.id(), "outcome", "failure",
                            "failure_category", classified.category()));
                    throw failure;
                }
                if (calls >= maxAttempts || candidateIndex == candidates.size() - 1) {
                    observations.event("ford.ai.provider.attempt", Map.of(
                            "provider", candidate.id(), "outcome", "failure",
                            "failure_category", classified.category()));
                    break;
                }
                var delay = retryDelay(classified, calls);
                if (delay.compareTo(maxRetryAfter) > 0) {
                    observations.event("ford.ai.provider.attempt", Map.of(
                            "provider", candidate.id(), "outcome", "failure",
                            "failure_category", classified.category()));
                    break;
                }
                observations.event("ford.ai.provider.attempt", Map.of(
                        "provider", candidate.id(), "outcome", "retry",
                        "failure_category", classified.category()));
                if (!delay.isZero()) {
                    retryDelay.delay(delay);
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IllegalStateException("No configured AI provider completed the request");
    }

    private Duration retryDelay(ProviderFailure failure, int attempt) {
        if (failure.retryAfter() != null) {
            return failure.retryAfter();
        }
        var backoff = Duration.ofMillis(100L << (attempt - 1));
        return backoff.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : backoff;
    }

    private static boolean isRetryable(ProviderFailure failure) {
        return ProviderFailure.RATE_LIMITED.equals(failure.category())
                || ProviderFailure.PROVIDER_UNAVAILABLE.equals(failure.category())
                || ProviderFailure.NETWORK.equals(failure.category());
    }

}
