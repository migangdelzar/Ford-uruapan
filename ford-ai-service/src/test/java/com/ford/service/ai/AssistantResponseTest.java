package com.ford.service.ai;

import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.api.ResponseKind;
import com.ford.service.ai.application.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantResponseTest {
    @Test
    void defaultsLegacyResponsesToAnswersAndPreservesExplicitClarificationKind() {
        var legacy = new AssistantResponse("Respuesta", false, "test", List.of());
        var clarification = new AssistantResponse("¿Cuál versión?", false, "rules", List.of(), null,
                null, com.ford.service.ai.api.ResponseEvidenceStatus.NOT_REQUIRED, ResponseKind.CLARIFICATION);

        assertThat(legacy.responseKind()).isEqualTo(ResponseKind.ANSWER);
        assertThat(clarification.responseKind()).isEqualTo(ResponseKind.CLARIFICATION);
    }

    @Test
    void assistantTurnCarriesTheValidatedFinalResponseKind() {
        var turn = new AssistantTurn("¿Cuál versión?", Intent.CATALOG, false, List.of(), "rules", "none",
                com.ford.service.ai.application.ConversationDecision.deterministicDefault(), null, List.of(),
                ResponseKind.CLARIFICATION);

        assertThat(turn.responseKind()).isEqualTo(ResponseKind.CLARIFICATION);
    }
}
