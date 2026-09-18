package com.ford.service.e2e;

import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.application.DefaultAssistantOrchestrator;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HumanTakeoverFlowTest {

    @Test
    void botDoesNotAnswerWhileHumanIsActive() {
        var orchestrator = new DefaultAssistantOrchestrator(new IntentRouter(), new ToolAuthorizationPolicy(),
                (request, context) -> throwIfCalled());

        var response = orchestrator.respond(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Sigue disponible?", "HUMAN_ACTIVE", false));

        assertThat(response.handoffRequired()).isTrue();
        assertThat(response.content()).contains("asesor humano");
    }

    private static com.ford.service.ai.api.AssistantResponse throwIfCalled() {
        throw new AssertionError("AI provider must not be called during human takeover");
    }
}
