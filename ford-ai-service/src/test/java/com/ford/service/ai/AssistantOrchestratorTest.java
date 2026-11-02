package com.ford.service.ai;

import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.DefaultAssistantOrchestrator;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantOrchestratorTest {

    @Test
    void humanActiveConversationBypassesTheModel() {
        var completion = mock(ChatCompletionPort.class);
        AssistantOrchestrator orchestrator = new DefaultAssistantOrchestrator(
                new IntentRouter(), new ToolAuthorizationPolicy(), completion);
        var request = new AssistantRequest(
                UUID.randomUUID(), UUID.randomUUID(), "ya está atendiendo una persona", "HUMAN_ACTIVE", false);

        var response = orchestrator.respond(request);

        assertThat(response.handoffRequired()).isTrue();
        assertThat(response.content()).isEqualTo("Un asesor ya está atendiendo su conversación.");
        verifyNoInteractions(completion);
    }

    @Test
    void delegatesNonHumanConversationToProviderBoundary() {
        var completion = mock(ChatCompletionPort.class);
        when(completion.complete(any(), any())).thenReturn(
                new AssistantResponse("Tenemos disponibilidad para la Ranger.", false, "ollama", List.of()));
        AssistantOrchestrator orchestrator = new DefaultAssistantOrchestrator(
                new IntentRouter(), new ToolAuthorizationPolicy(), completion);

        var response = orchestrator.respond(new AssistantRequest(
                UUID.randomUUID(), UUID.randomUUID(), "¿Qué Ranger tienen?", "BOT_ACTIVE", false));

        assertThat(response.content()).contains("disponibilidad");
    }

    @Test
    void recordsOnlyBoundedToolAuthorizationOutcome() {
        var completion = mock(ChatCompletionPort.class);
        when(completion.complete(any(), any())).thenReturn(
                new AssistantResponse("Respuesta segura.", false, "test", List.of()));
        var trace = new DecisionTraceCollector();
        var orchestrator = new DefaultAssistantOrchestrator(new IntentRouter(), new ToolAuthorizationPolicy(), completion);
        var request = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "¿Qué hay?", "BOT_ACTIVE",
                null, false, null, null, List.of(), null, trace);

        orchestrator.respond(request);

        assertThat(trace.snapshot()).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo("authorization");
            assertThat(event.outcome()).isEqualTo("skipped");
            assertThat(event.evidenceCount()).isZero();
        });
    }

}
