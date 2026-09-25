package com.ford.service.ai;

import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.ModelRequirements;
import com.ford.service.ai.application.ProviderFailure;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderRoutingChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        var throttled = new RecordingPort(cloud.id(), rateLimitedFailure());
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

    private static OpenAIServiceException rateLimitedFailure() {
        var failure = openAiFailure(429);
        when(failure.headers()).thenReturn(Headers.builder().put("retry-after", "0").build());
        return failure;
    }

    private static OpenAIServiceException openAiFailure(int status) {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(status);
        when(failure.headers()).thenReturn(Headers.builder().build());
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
}
