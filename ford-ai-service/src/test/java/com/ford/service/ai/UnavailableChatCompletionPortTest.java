package com.ford.service.ai;

import com.ford.service.ai.application.port.out.UnavailableChatCompletionPort;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.api.AssistantRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnavailableChatCompletionPortTest {

    @Test
    void reportsThatNoAiProviderIsConfigured() {
        var port = new UnavailableChatCompletionPort();
        var request = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "Hola", "BOT_ACTIVE", false);

        assertThatThrownBy(() -> port.complete(request,
                new ToolContext(request.tenantId(), request.conversationId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No AI provider is configured");
    }
}
