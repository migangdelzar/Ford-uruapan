package com.ford.service.ai;

import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadQualificationPolicy;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeadQualificationPolicyTest {

    private final LeadQualificationPolicy policy = new LeadQualificationPolicy();

    @Test
    void keepsAnInformationalPriceQuestionAutomated() {
        var assessment = policy.assess("¿Qué precio tiene la Ford Ranger?", null, decision());

        assertThat(assessment.stage()).isEqualTo(LeadStage.QUALIFYING);
        assertThat(assessment.temperature()).isEqualTo(LeadTemperature.COLD);
        assertThat(assessment.shouldEscalate()).isFalse();
    }

    @Test
    void accumulatesUserSignalsFromHotMemoryWithoutEscalatingTooEarly() {
        var memory = "Historial reciente:\n"
                + "- usuario: Me interesa la Ford Ranger\n"
                + "- asistente: Con gusto le comparto información.\n"
                + "- usuario: ¿Puedo financiarla?";

        var assessment = policy.assess("¿Qué opciones tengo este mes?", memory, decision());

        assertThat(assessment.stage()).isEqualTo(LeadStage.QUALIFIED);
        assertThat(assessment.temperature()).isEqualTo(LeadTemperature.WARM);
        assertThat(assessment.shouldEscalate()).isFalse();
        assertThat(assessment.signals()).contains("vehicle_interest", "financing", "near_term");
    }

    @Test
    void escalatesAHighConfidenceTransactionalRequest() {
        var assessment = policy.assess(
                "Me interesa comprar la Ranger este mes. Mi teléfono es 4521234567.", null, decision());

        assertThat(assessment.stage()).isEqualTo(LeadStage.QUALIFIED);
        assertThat(assessment.temperature()).isEqualTo(LeadTemperature.HOT);
        assertThat(assessment.shouldEscalate()).isTrue();
        assertThat(assessment.signals()).contains("explicit_action", "near_term", "contact_details");
    }

    @Test
    void keepsAppointmentRequirementsInformationalUntilTheCustomerRequestsScheduling() {
        var assessment = policy.assess(
                "¿Qué requisitos necesito para una prueba de manejo?", null, decision());

        assertThat(assessment.shouldEscalate()).isFalse();
    }

    @Test
    void escalatesWhenTheCustomerRequestsAnAppointment() {
        var assessment = policy.assess("Quiero agendar una cita", null, decision());

        assertThat(assessment.shouldEscalate()).isTrue();
        assertThat(assessment.signals()).contains("explicit_action");
    }

    @Test
    void escalatesWhenTheCustomerStatesANearTermPurchaseIntent() {
        var assessment = policy.assess("Comprar la Ranger este mes", null, decision());

        assertThat(assessment.shouldEscalate()).isTrue();
        assertThat(assessment.signals()).contains("explicit_action", "near_term");
    }

    @Test
    void enrichesTheDecisionWithDeterministicLeadEvidence() {
        var assessment = policy.assess("¿Qué precio tiene la Ranger?", null, decision());

        var enriched = policy.enrich(decision(), assessment);

        assertThat(enriched.leadStage()).isEqualTo(LeadStage.QUALIFYING);
        assertThat(enriched.leadTemperature()).isEqualTo(LeadTemperature.COLD);
        assertThat(enriched.evidence())
                .anySatisfy(evidence -> assertThat(evidence.reasonCode())
                        .isEqualTo("deterministic_lead_signal_score"));
    }

    private static ConversationDecision decision() {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, List.of());
    }
}
