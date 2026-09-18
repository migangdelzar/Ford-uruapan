package com.ford.service.leads;

import com.ford.service.leads.application.DefaultLeadScoringPolicy;
import com.ford.service.leads.application.ConversationSignals;
import com.ford.service.leads.domain.LeadProfile;
import com.ford.service.leads.domain.LeadScoreBand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLeadScoringPolicyTest {

    private final DefaultLeadScoringPolicy policy = new DefaultLeadScoringPolicy();

    @Test
    void scoresConsentedReadyBuyerAsHotWithReasons() {
        var profile = new LeadProfile(
                "Ana", "+524521234567", "ana@example.com", "Ranger", "600k-800k",
                "100k-200k", "employed", true, true);
        var score = policy.score(profile, new ConversationSignals(true, true, true, true, true));

        assertThat(score.band()).isEqualTo(LeadScoreBand.HOT);
        assertThat(score.value()).isGreaterThanOrEqualTo(70);
        assertThat(score.reasons()).contains("explicit_human_request", "financing_intent");
    }

    @Test
    void doesNotScoreAsHotWithoutConsent() {
        var profile = new LeadProfile(
                "Ana", "+524521234567", null, "Ranger", null,
                null, null, false, false);
        var score = policy.score(profile, new ConversationSignals(false, false, false, false, true));

        assertThat(score.band()).isNotEqualTo(LeadScoreBand.HOT);
        assertThat(score.reasons()).contains("consent_missing");
    }
}
