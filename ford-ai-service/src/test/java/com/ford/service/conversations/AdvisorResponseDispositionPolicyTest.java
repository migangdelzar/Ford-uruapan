package com.ford.service.conversations;

import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.AdvisorResponseDispositionPolicy;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorResponseDispositionPolicyTest {
    private final AdvisorResponseDispositionPolicy policy = new AdvisorResponseDispositionPolicy();
    private static final Instant NOW = Instant.parse("2026-10-28T17:00:00Z");

    @Test
    void sensitiveDocumentRoutesBeforeAssistantAndDoesNotNeedDocumentText() {
        var inbound = inbound("Te envío los documentos", InboundMessageKind.DOCUMENT,
                List.of(new InboundAttachment("opaque-media-id", "application/pdf", "estado_de_cuenta.pdf", 100L)));
        assertThat(policy.beforeAssistant(inbound, 0).route())
                .isEqualTo(AdvisorResponseDisposition.Route.MANDATORY_HANDOFF);
    }

    @Test
    void humanRequestAndSeriousComplaintTakeMandatoryHandoffRoute() {
        assertThat(policy.beforeAssistant(inbound("Quiero hablar con un asesor", InboundMessageKind.TEXT, List.of()), 0).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.HUMAN_REQUEST);
        assertThat(policy.beforeAssistant(inbound("Esto es un fraude, presentaré una denuncia", InboundMessageKind.TEXT, List.of()), 0).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.SERIOUS_COMPLAINT);
    }

    @Test
    void declinedHumanOfferAndGeneralLegalQuestionDoNotBecomeMandatoryHandoffs() {
        assertThat(policy.beforeAssistant(inbound("No quiero hablar con un asesor", InboundMessageKind.TEXT, List.of()), 0).route())
                .isEqualTo(AdvisorResponseDisposition.Route.AUTOMATIC);
        assertThat(policy.beforeAssistant(inbound("¿Qué dice la ley sobre garantías de autos?", InboundMessageKind.TEXT,
                List.of()), 0).route()).isEqualTo(AdvisorResponseDisposition.Route.AUTOMATIC);
    }

    @Test
    void repeatedSubstantiveFailureEscalatesAfterSecondFailureOnly() {
        var inbound = inbound("¿Qué opciones hay?", InboundMessageKind.TEXT, List.of());
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("No pude confirmarlo.", false)).route())
                .isEqualTo(AdvisorResponseDisposition.Route.REVIEW);
        assertThat(policy.afterAssistant(inbound, 1, new AssistantReply("No pude confirmarlo.", false)).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.REPEATED_FAILURE);
    }

    @Test
    void routineQuestionsStayAutomaticWhileModelSuggestionAndMissingEvidenceRequireReview() {
        var inbound = inbound("¿Cuál es el precio publicado?", InboundMessageKind.TEXT, List.of());
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("Puedo compartir la información publicada.", false)).route())
                .isEqualTo(AdvisorResponseDisposition.Route.AUTOMATIC);
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("¿Qué modelo busca?", true)).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.MODEL_REQUESTED_REVIEW);
        var missing = new AssistantReply("No pude confirmarlo.", false, null, null, List.of(
                new DecisionTraceEvent("evidence", "response_evidence_assessed", null, "none", null,
                        "unknown", 0, NOW)));
        assertThat(policy.afterAssistant(inbound, 0, missing).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.RESPONSE_REVIEW);
    }

    @Test
    void financingQuestionsAreRoutineButConcreteCommitmentsNeedReview() {
        var inbound = inbound("¿Qué opciones de financiamiento ofrecen?", InboundMessageKind.TEXT, List.of());
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("Le explico las opciones disponibles.", false)).route())
                .isEqualTo(AdvisorResponseDisposition.Route.AUTOMATIC);
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("Tu crédito ya está aprobado.", false)).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.FINANCE_COMMITMENT);
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("Te aplicamos un descuento de $5,000.", false)).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.DISCOUNT_COMMITMENT);
        assertThat(policy.afterAssistant(inbound, 0, new AssistantReply("Tenemos unidades disponibles.", false)).reason())
                .isEqualTo(AdvisorResponseDisposition.Reason.INVENTORY_PROMISE);
    }

    @Test
    void regularPromotionLanguageIsNotACommitment() {
        var inbound = inbound("¿Qué promociones publicadas hay?", InboundMessageKind.TEXT, List.of());
        assertThat(policy.afterAssistant(inbound, 0,
                new AssistantReply("La promoción publicada aplica según sus condiciones.", false)).route())
                .isEqualTo(AdvisorResponseDisposition.Route.AUTOMATIC);
    }

    private static AcceptedInboundMessage inbound(String text, InboundMessageKind kind, List<InboundAttachment> attachments) {
        return new AcceptedInboundMessage("provider-id", "participant-id", text, NOW, kind, attachments);
    }
}
