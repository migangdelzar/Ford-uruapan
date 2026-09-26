package com.ford.service.ai;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderRoutingChatCompletionPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRoutingAttemptBudgetTest {

    @Test
    void spendsOneGlobalCallOnPrimaryThenUsesTheApprovedFallback() {
        var primary = new FailingPort("openrouter-free");
        var fallback = new SuccessfulPort("openai", "fallback");
        var routing = new ProviderRoutingChatCompletionPort(
                List.of(primary, fallback),
                new DefaultModelProviderPolicy(List.of(
                        provider("openrouter-free", true), provider("openai", true))),
                2, Duration.ZERO, ignored -> { });

        var response = routing.complete(request(), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("fallback");
        assertThat(primary.calls).isEqualTo(1);
        assertThat(fallback.calls).isEqualTo(1);
    }

    private static ProviderDescriptor provider(String id, boolean cloud) {
        return new ProviderDescriptor(id, new ProviderCapabilities(true, true, true), true, cloud, id + "-v1");
    }

    private static AssistantRequest request() {
        return new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "promociones",
                "BOT_ACTIVE", false);
    }

    private static final class FailingPort implements ProviderChatCompletionPort {
        private final String providerId;
        private int calls;

        private FailingPort(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public AssistantResponse complete(AssistantRequest request, ToolContext context) {
            calls++;
            throw new RateLimitException();
        }
    }

    private static final class SuccessfulPort implements ProviderChatCompletionPort {
        private final String providerId;
        private final String content;
        private int calls;

        private SuccessfulPort(String providerId, String content) {
            this.providerId = providerId;
            this.content = content;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public AssistantResponse complete(AssistantRequest request, ToolContext context) {
            calls++;
            return new AssistantResponse(content, false, providerId, List.of());
        }
    }

    private static final class RateLimitException extends RuntimeException {
    }
}
