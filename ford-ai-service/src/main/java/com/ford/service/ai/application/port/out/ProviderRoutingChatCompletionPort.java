package com.ford.service.ai.application.port.out;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AiProviderFailureDiagnostics;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ModelRequirements;
import com.ford.service.ai.application.ProviderFailure;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Applies the application provider policy to the concrete Spring AI adapters. */
public final class ProviderRoutingChatCompletionPort implements ChatCompletionPort {

    private final Map<String, ProviderChatCompletionPort> ports;
    private final ModelProviderPolicy policy;

    public ProviderRoutingChatCompletionPort(List<? extends ProviderChatCompletionPort> ports,
                                             ModelProviderPolicy policy) {
        Objects.requireNonNull(ports, "ports must not be null");
        this.ports = ports.stream().collect(Collectors.toUnmodifiableMap(
                ProviderChatCompletionPort::providerId, Function.identity(), (left, right) -> left));
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
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
        for (var index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var port = ports.get(candidate.id());
            if (port == null) {
                throw new IllegalStateException("Selected AI provider is not configured: " + candidate.id());
            }
            try {
                return port.complete(request, context);
            } catch (RuntimeException failure) {
                if (!isRetryable(AiProviderFailureDiagnostics.classify(failure))
                        || index == candidates.size() - 1) {
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("No configured AI provider completed the request");
    }

    private static boolean isRetryable(ProviderFailure failure) {
        return ProviderFailure.RATE_LIMITED.equals(failure.category())
                || ProviderFailure.PROVIDER_UNAVAILABLE.equals(failure.category())
                || ProviderFailure.NETWORK.equals(failure.category());
    }
}
