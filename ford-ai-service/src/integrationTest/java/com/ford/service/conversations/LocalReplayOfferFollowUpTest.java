package com.ford.service.conversations;

import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayOfferFollowUpTest {
    @Test
    void doesNotTreatExactVehiclePriceFollowUpAsPromotion() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence(
                "¿Ese monto corresponde a la XLT que acabamos de revisar?", null)).isFalse();
    }

    @Test
    void explicitPromotionContextStillRequiresPromotionEvidence() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence(
                "¿Ese monto corresponde al descuento de la promoción vigente?", null)).isTrue();
    }

    @Test
    void recognizesTurnFiftyNineBenefitFollowUpWithoutOffersRouting() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence(
                "¿Ese beneficio aplica específicamente a la Ranger XLT?", null)).isTrue();
    }

    @Test
    void recognizesExplicitOffersRouting() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence("consulta general", ToolFamily.OFFERS)).isTrue();
    }
}
