package com.ford.service.ai;

import com.ford.service.ai.application.QualityDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class QualityDecisionTest {
    @Test
    void createsExplicitPassRepairClarifyHandoffAndBlockOutcomes() {
        assertThat(QualityDecision.pass().status()).isEqualTo(QualityDecision.Status.PASS);
        assertThat(QualityDecision.repair("unsupported_fact_claim").status())
                .isEqualTo(QualityDecision.Status.REPAIR);
        assertThat(QualityDecision.clarify("ambiguous_vehicle_trim").status())
                .isEqualTo(QualityDecision.Status.CLARIFY);
        assertThat(QualityDecision.handoff("human_requested").status())
                .isEqualTo(QualityDecision.Status.HANDOFF);
        assertThat(QualityDecision.block("unsafe_output").status())
                .isEqualTo(QualityDecision.Status.BLOCK);
    }

    @Test
    void rejectsPassingDecisionWithFailureReason() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new QualityDecision(QualityDecision.Status.PASS, "unsupported_fact_claim"));
    }
}
