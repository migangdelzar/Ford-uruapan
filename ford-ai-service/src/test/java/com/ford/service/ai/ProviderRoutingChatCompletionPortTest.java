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
import com.ford.service.observability.AiObservationService;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;
import java.net.SocketTimeoutException;
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
    void routesSensitiveRequestsToTheLocalProvider() {
        var cloud = provider("openai", true);
        var local = provider("ollama", false);
        var port = new ProviderRoutingChatCompletionPort(
                List.of(new StubPort(cloud.id(), "cloud"), new StubPort(local.id(), "local")),
                new DefaultModelProviderPolicy(List.of(cloud, local)));

        var response = port.complete(request(true), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("local");
        assertThat(response.provider()).isEqualTo("ollama");
    }

    @Test
    void fallsBackToTheNextEligibleProviderAfterRateLimiting() {
        var cloud = provider("openai", true);
        var local = provider("ollama", false);
        var throttled = new RecordingPort(cloud.id(), openAiFailure(429, "4"));
        var fallback = new StubPort(local.id(), "local-recovery");
        var port = new ProviderRoutingChatCompletionPort(List.of(throttled, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, local)));

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.content()).isEqualTo("local-recovery");
        assertThat(throttled.calls).isEqualTo(1);
    }

    @Test
    void doesNotFallBackAfterAuthenticationOrInvalidRequestFailures() {
        for (var status : List.of(401, 400)) {
            var cloud = provider("openai", true);
            var local = provider("ollama", false);
            var failure = openAiFailure(status);
            var primary = new RecordingPort(cloud.id(), failure);
            var fallback = new StubPort(local.id(), "must-not-run");
            var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                    new DefaultModelProviderPolicy(List.of(cloud, local)));

            assertThatThrownBy(() -> port.complete(request(false),
                    new ToolContext(UUID.randomUUID(), UUID.randomUUID())))
                    .isSameAs(failure);
            assertThat(primary.calls).isEqualTo(1);
        }
    }

    @Test
    void retriesTransientFailureOnceAndHonorsRetryAfterWithinTheConfiguredBudget() {
        var cloud = provider("openai", true);
        var delays = new java.util.ArrayList<Duration>();
        var observations = mock(AiObservationService.class);
        var primary = new SequencedPort(cloud.id(), List.of(openAiFailure(429, "2")), "cloud-recovered");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary),
                new DefaultModelProviderPolicy(List.of(cloud)), 2, Duration.ofSeconds(3), delays::add,
                observations);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("cloud-recovered");
        assertThat(primary.calls).isEqualTo(2);
        assertThat(delays).containsExactly(Duration.ofSeconds(2));
        verify(observations).event("ford.ai.provider.attempt", java.util.Map.of(
                "provider", "openai", "outcome", "retry", "failure_category", "rate_limited"));
    }

    @Test
    void retriesTransientNetworkFailureOnceWithBoundedBackoff() {
        var cloud = provider("openai", true);
        var delays = new java.util.ArrayList<Duration>();
        var primary = new SequencedPort(cloud.id(),
                List.of(new RuntimeException("temporary network timeout",
                        new SocketTimeoutException("temporary network timeout"))), "network-recovered");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary),
                new DefaultModelProviderPolicy(List.of(cloud)), 2, Duration.ofSeconds(3), delays::add);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("network-recovered");
        assertThat(primary.calls).isEqualTo(2);
        assertThat(delays).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void skipsSameProviderRetryWhenRetryAfterExceedsBudgetAndFallsBack() {
        var cloud = provider("openrouter-free", true);
        var local = provider("ollama", false);
        var delays = new java.util.ArrayList<Duration>();
        var primary = new RecordingPort(cloud.id(), openAiFailure(429, "4"));
        var fallback = new StubPort(local.id(), "local-after-long-retry-after");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, local)), 2, Duration.ofSeconds(3), delays::add);

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(primary.calls).isEqualTo(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void limitsFreeProviderToTwoCallsBeforeFallbackWhenItReturnsRepeated429() {
        var cloud = provider("openrouter-free", true);
        var local = provider("ollama", false);
        var primary = new SequencedPort(cloud.id(), List.of(openAiFailure(429, null), openAiFailure(429, null)), "unused");
        var fallback = new StubPort(local.id(), "local-after-rate-limit");
        var port = new ProviderRoutingChatCompletionPort(List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(cloud, local)), 2, Duration.ofSeconds(3), ignored -> { });

        var response = port.complete(request(false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(primary.calls).isEqualTo(2);
    }

    @Test
    void failsClosedWhenPolicySelectsAnUnconfiguredProvider() {
        var openAi = provider("openai", true);
        var port = new ProviderRoutingChatCompletionPort(
                List.of(new StubPort(openAi.id(), "cloud")),
                new DefaultModelProviderPolicy(List.of(openAi, provider("ollama", false))));

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
