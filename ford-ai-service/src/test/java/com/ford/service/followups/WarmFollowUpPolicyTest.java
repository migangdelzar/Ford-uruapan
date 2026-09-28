package com.ford.service.followups;

import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.followups.application.WarmFollowUpPolicy;
import com.ford.service.followups.domain.FollowUpConsent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WarmFollowUpPolicyTest {

    private final WarmFollowUpPolicy policy = new WarmFollowUpPolicy();

    @Test
    void recordsOnlyAnExplicitAssistantAdvisorOfferForWarmLead() {
        assertThat(policy.isOffer(LeadTemperature.WARM,
                "Puedo pedir que un asesor te contacte para revisar opciones." )).isTrue();
        assertThat(policy.isOffer(LeadTemperature.COLD,
                "Puedo pedir que un asesor te contacte para revisar opciones.")).isFalse();
        assertThat(policy.isOffer(LeadTemperature.WARM,
                "El asesor puede ayudarte.")).isFalse();
    }

    @Test
    void recognizesClearConsentAndDeclineWithoutTreatingQuestionsAsConsent() {
        assertThat(policy.consent("Sí, que me contacte un asesor.")).isEqualTo(FollowUpConsent.ACCEPT);
        assertThat(policy.consent("No gracias")).isEqualTo(FollowUpConsent.DECLINE);
        assertThat(policy.consent("¿Qué promociones tienen?")).isEqualTo(FollowUpConsent.NONE);
        assertThat(policy.consent("Tal vez después")).isEqualTo(FollowUpConsent.NONE);
        assertThat(policy.consent("Sí, pero no por ahora")).isEqualTo(FollowUpConsent.NONE);
        assertThat(policy.consent("No me llames")).isEqualTo(FollowUpConsent.DECLINE);
    }
}
