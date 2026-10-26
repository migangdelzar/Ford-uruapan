package com.ford.service.conversations;

import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayOfferFollowUpTest {
    @Test
    void recognizesExactTurnTwelvePromotionFollowUp() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence(
                "¿Ese monto corresponde a la XLT que acabamos de revisar?", ToolFamily.OFFERS)).isTrue();
    }

    @Test
    void recognizesExactTurnTwelveWordingWithoutOffersRouting() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence(
                "¿Ese monto corresponde a la XLT que acabamos de revisar?", null)).isTrue();
    }

    @Test
    void recognizesExplicitOffersRouting() {
        assertThat(LocalReplayOfferFollowUp.requiresPromotionEvidence("consulta general", ToolFamily.OFFERS)).isTrue();
    }
}
