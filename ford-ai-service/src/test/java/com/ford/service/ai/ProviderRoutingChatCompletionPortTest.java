package com.ford.service.ai;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.ModelRequirements;
import com.ford.service.ai.application.ProviderFailure;
import com.ford.service.ai.application.ProviderRetryDelay;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderRoutingChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.AssistantGeneration;
import com.ford.service.observability.AiObservationService;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ProviderRoutingChatCompletionPortTest {

    @Test
    void routesToTheProviderSelectedByPrivacyAndCapabilityPolicy() {
        var cloud = provider("openai", true);
        var local = provider("ollama", false);
        var port = new ProviderRoutingChatCompletionPort(
                List.of(new StubPort(cloud.id(), "cloud"), new StubPort(local.id(), "local")),
                new DefaultModelProviderPolicy(List.of(cloud, local)));

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("cloud");
        assertThat(response.provider()).isEqualTo("openai");
    }

    @Test
    void routesSensitiveRequestsToTheConfiguredPrimaryEvenWhenTextIsSensitive() {
        var cloud = provider("openai", true);
        var local = provider("ollama", false);
        var port = new ProviderRoutingChatCompletionPort(
                List.of(new StubPort(cloud.id(), "cloud"), new StubPort(local.id(), "local")),
                new DefaultModelProviderPolicy(List.of(cloud, local)));

        var response = port.complete(request(true), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("cloud");
        assertThat(response.provider()).isEqualTo("openai");
    }

    @Test
    void fallsBackToTheNextEligibleProviderAfterRateLimiting() {
        var cloud = provider("openai", true);
        var local = provider("ollama", false);
        var throttled = new RecordingPort(cloud.id(), new RateLimitException());
        var fallback = new StubPort(local.id(), "local-recovery");
        var port = new ProviderRoutingChatCompletionPort(List.of(throttled, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, local)));

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.content()).isEqualTo("local-recovery");
        assertThat(throttled.calls).isEqualTo(1);
    }

    @Test
    void fallsBackToTheNextEligibleProviderAfterStructuredResponseParsingFails() {
        var primary = new RecordingPort("openrouter",
                new RuntimeException("invalid provider response", new JsonParseException("invalid provider response stream")));
        var fallback = new StubPort("cloudflare", "recovered-with-verified-provider");
        var policy = new DefaultModelProviderPolicy(List.of(
                provider("openrouter", true), provider("cloudflare", true)));
        var port = new ProviderRoutingChatCompletionPort(
                List.of(primary, fallback), policy, 6, Duration.ZERO, ignored -> { });

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("cloudflare");
        assertThat(response.content()).isEqualTo("recovered-with-verified-provider");
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void fallsBackAfterSpringAiJacksonThreeStructuredResponseConversionFails() {
        var primary = new RecordingPort("cloudflare",
                tools.jackson.databind.exc.MismatchedInputException.from(
                        null, AssistantGeneration.class, "invalid structured response"));
        var fallback = new StubPort("groq", "recovered-with-verified-provider");
        var policy = new DefaultModelProviderPolicy(List.of(
                provider("cloudflare", true), provider("groq", true)));
        var port = new ProviderRoutingChatCompletionPort(
                List.of(primary, fallback), policy, 6, Duration.ZERO, ignored -> { });

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("groq");
        assertThat(response.content()).isEqualTo("recovered-with-verified-provider");
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void returnsSafeInformationGapOnlyAfterAllProvidersFailForVolatileInformation() {
        var primary = new RecordingPort("cloudflare",
                tools.jackson.databind.exc.MismatchedInputException.from(
                        null, AssistantGeneration.class, "invalid structured response"));
        var fallback = new RecordingPort("groq", new RateLimitException());
        var policy = new DefaultModelProviderPolicy(List.of(
                provider("cloudflare", true), provider("groq", true)));
        var port = new ProviderRoutingChatCompletionPort(
                List.of(primary, fallback), policy, 6, Duration.ZERO, ignored -> { });

        var response = port.complete(volatileRequest(), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(primary.calls).isEqualTo(1);
        assertThat(fallback.calls).isEqualTo(1);
        assertThat(response.content()).contains("no lo tengo confirmado");
        assertThat(response.provider()).isEqualTo("fallback");
    }

    @Test
    void walksTheWholeOrderedProviderListUntilAProviderSucceeds() {
        var ids = List.of("openai", "groq", "gemini", "cloudflare", "openrouter", "ollama");
        var descriptors = ids.stream().map(id -> provider(id, !id.equals("ollama"))).toList();
        var ports = List.<ProviderChatCompletionPort>of(
                new RecordingPort("openai", new RateLimitException()),
                new RecordingPort("groq", new RuntimeException("temporary network issue", new IOException("connection reset"))),
                new RecordingPort("gemini", new RuntimeException("temporary network timeout",
                        new SocketTimeoutException("temporary network timeout"))),
                new StubPort("cloudflare", "recovered"),
                new StubPort("openrouter", "must-not-run"),
                new StubPort("ollama", "must-not-run"));
        var port = new ProviderRoutingChatCompletionPort(ports, new DefaultModelProviderPolicy(descriptors),
                6, Duration.ZERO, ignored -> { });

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("cloudflare");
        assertThat(response.content()).isEqualTo("recovered");
        assertThat(((RecordingPort) ports.get(0)).calls).isEqualTo(1);
        assertThat(((RecordingPort) ports.get(1)).calls).isEqualTo(1);
        assertThat(((RecordingPort) ports.get(2)).calls).isEqualTo(1);
    }

    @Test
    void fallsBackWhenRetryAfterExceedsTheWaitBudgetInsteadOfStoppingTheChain() {
        var primary = new RecordingPort("openai", openAiFailure(429, "30"));
        var fallback = new StubPort("groq", "recovered-without-waiting");
        var delays = new java.util.ArrayList<Duration>();
        var port = new ProviderRoutingChatCompletionPort(
                List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(provider("openai", true), provider("groq", true))),
                6, Duration.ofSeconds(3), delays::add);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("groq");
        assertThat(delays).isEmpty();
    }

    @Test
    void fallsBackAfterProviderRejectsCompletionFollowingReadOnlyToolResult() {
        var primary = new RecordingPort("groq",
                new com.ford.service.ai.application.ProviderFollowupRejectedException(openAiFailure(400)));
        var qwen = new StubPort("ollama", "respuesta local qwen");
        var policy = new DefaultModelProviderPolicy(List.of(provider("groq", true), provider("ollama", false)));
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, qwen), policy,
                6, Duration.ZERO, ignored -> { });

        var response = port.complete(volatileRequest(), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.content()).isEqualTo("respuesta local qwen");
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void usesFallbackAfterProviderAuthenticationOrBillingFailureButNotMalformedRequest() {
        for (var status : List.of(401, 402)) {
            var primaryDescriptor = provider("deepseek", true);
            var fallbackDescriptor = provider("openrouter", true);
            var primaryFailure = openAiFailure(status);
            var primary = new RecordingPort(primaryDescriptor.id(), primaryFailure);
            var fallback = new SequencedPort(fallbackDescriptor.id(), List.of(), "fallback answer");
            var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                    new DefaultModelProviderPolicy(List.of(primaryDescriptor, fallbackDescriptor)),
                    2, Duration.ZERO, ignored -> { });

            var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

            assertThat(response.content()).isEqualTo("fallback answer");
            assertThat(primary.calls).isEqualTo(1);
            assertThat(fallback.calls).isEqualTo(1);
        }

        var primaryDescriptor = provider("deepseek", true);
        var fallbackDescriptor = provider("openrouter", true);
        var badRequest = openAiFailure(400);
        var primary = new RecordingPort(primaryDescriptor.id(), badRequest);
        var fallback = new SequencedPort(fallbackDescriptor.id(), List.of(), "must-not-run");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(primaryDescriptor, fallbackDescriptor)),
                2, Duration.ZERO, ignored -> { });

        assertThatThrownBy(() -> port.complete(request(false),
                new ToolContext(UUID.randomUUID(), UUID.randomUUID()))).isSameAs(badRequest);
        assertThat(primary.calls).isEqualTo(1);
        assertThat(fallback.calls).isZero();
    }

    @Test
    void fallsBackAfterRateLimitAndHonorsRetryAfterWithinTheConfiguredBudget() {
        var cloud = provider("openai", true);
        var delays = new java.util.ArrayList<Duration>();
        var observations = mock(AiObservationService.class);
        var primary = new RecordingPort(cloud.id(), new RateLimitException());
        var fallback = new StubPort("ollama", "cloud-recovered");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, provider("ollama", false))), 2, Duration.ofSeconds(3), delays::add,
                observations);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("cloud-recovered");
        assertThat(primary.calls).isEqualTo(1);
        assertThat(delays).containsExactly(Duration.ofMillis(100));
        verify(observations).event("ford.ai.provider.attempt", java.util.Map.of(
                "provider", "openai", "outcome", "fallback", "failure_category", "rate_limited"));
    }

    @Test
    void fallsBackAfterTransientNetworkFailureWithBoundedBackoff() {
        var cloud = provider("openai", true);
        var delays = new java.util.ArrayList<Duration>();
        var primary = new SequencedPort(cloud.id(),
                List.of(new RuntimeException("temporary network timeout",
                        new SocketTimeoutException("temporary network timeout"))), "network-recovered");
        var fallback = new StubPort("ollama", "network-recovered");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, provider("ollama", false))), 2, Duration.ofSeconds(3), delays::add);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("network-recovered");
        assertThat(primary.calls).isEqualTo(1);
        assertThat(delays).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void skipsOverBudgetRetryAfterAndContinuesToFallback() {
        var cloud = provider("openrouter-free", true);
        var local = provider("ollama", false);
        var delays = new java.util.ArrayList<Duration>();
        var primary = new RecordingPort(cloud.id(), new RateLimitException());
        var fallback = new StubPort(local.id(), "local-after-long-retry-after");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, local)), 2, Duration.ZERO, delays::add);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(primary.calls).isEqualTo(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void doesNotRepeatFreeProviderAfterRateLimitBeforeFallback() {
        var cloud = provider("openrouter-free", true);
        var local = provider("ollama", false);
        var primary = new RecordingPort(cloud.id(), new RateLimitException());
        var fallback = new StubPort(local.id(), "local-after-rate-limit");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, local)), 2, Duration.ofSeconds(3), ignored -> { });

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void failsClosedWhenPolicySelectsAnUnconfiguredProvider() {
        var openAi = provider("openai", true);
        var port = new ProviderRoutingChatCompletionPort(
                List.of(new StubPort(openAi.id(), "cloud")),
                new DefaultModelProviderPolicy(List.of(provider("ollama", false))));

        assertThatIllegalStateException().isThrownBy(() ->
                port.complete(request(true), new ToolContext(UUID.randomUUID(), UUID.randomUUID())))
                .withMessageContaining("ollama");
    }

    private static ProviderDescriptor provider(String id, boolean cloud) {
        return new ProviderDescriptor(id, new ProviderCapabilities(true, true, true), true, cloud, id + "-v1");
    }

    private static OpenAIServiceException openAiFailure(int status) {
        return openAiFailure(status, null);
    }

    private static OpenAIServiceException openAiFailure(int status, String retryAfter) {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(status);
        var headers = Headers.builder();
        if (retryAfter != null) {
            headers.put("retry-after", retryAfter);
        }
        when(failure.headers()).thenReturn(headers.build());
        return failure;
    }

    private static AssistantRequest request(boolean sensitive) {
        return new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "¿Qué vehículo tienen?",
                "BOT_ACTIVE", sensitive);
    }

    private static AssistantRequest volatileRequest() {
        var decision = new com.ford.service.ai.application.ConversationDecision(
                com.ford.service.ai.application.ConversationIntent.KNOWLEDGE,
                com.ford.service.ai.application.LeadTemperature.UNKNOWN,
                com.ford.service.ai.application.LeadStage.NEW,
                com.ford.service.ai.application.HandoffRecommendation.NONE,
                com.ford.service.ai.application.ToolFamily.KNOWLEDGE_SEARCH,
                com.ford.service.ai.application.SensitiveDataLevel.NONE, List.of());
        return new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "¿Qué rendimiento tiene la Ranger?",
                "BOT_ACTIVE", null, false, decision);
    }

    private record StubPort(String providerId, String value) implements ProviderChatCompletionPort {
        @Override
        public AssistantResponse complete(AssistantRequest request, ToolContext context) {
            return new AssistantResponse(value, false, providerId, List.of());
        }
    }

    private static final class RecordingPort implements ProviderChatCompletionPort {
        private final String providerId;
        private final RuntimeException failure;
        private int calls;

        private RecordingPort(String providerId, RuntimeException failure) {
            this.providerId = providerId;
            this.failure = failure;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public AssistantResponse complete(AssistantRequest request, ToolContext context) {
            calls++;
            throw failure;
        }
    }

    private static final class RateLimitException extends RuntimeException {
    }

    private static final class SequencedPort implements ProviderChatCompletionPort {
        private final String providerId;
        private final List<RuntimeException> failures;
        private final String value;
        private int calls;

        private SequencedPort(String providerId, List<RuntimeException> failures, String value) {
            this.providerId = providerId;
            this.failures = failures;
            this.value = value;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public AssistantResponse complete(AssistantRequest request, ToolContext context) {
            calls++;
            if (calls <= failures.size()) {
                throw failures.get(calls - 1);
            }
            return new AssistantResponse(value, false, providerId, List.of());
        }
    }
}
