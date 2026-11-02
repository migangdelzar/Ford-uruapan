package com.ford.service.conversations;

import com.ford.service.conversations.application.AdvisorResponseRoutingPolicy;
import com.ford.service.conversations.application.AdvisorResponseRoutingPolicy.Signal;
import com.ford.service.conversations.application.AdvisorResponseRoutingPolicy.Signals;
import com.ford.service.conversations.domain.AdvisorResponseDisposition.Reason;
import com.ford.service.conversations.domain.AdvisorResponseDisposition.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AdvisorResponseRoutingPolicyTest {
    private final AdvisorResponseRoutingPolicy policy = new AdvisorResponseRoutingPolicy();

    @ParameterizedTest
    @EnumSource(Signal.class)
    void routesEachServerOwnedSignal(Signal signal) {
        var result = policy.decide(new Signals(Set.of(signal), 0));
        assertThat(result.reason().name()).isEqualTo(signal.name());
        assertThat(result.route()).isEqualTo(switch (signal) {
            case HUMAN_REQUEST, SERIOUS_COMPLAINT, SENSITIVE_DOCUMENT -> Route.MANDATORY_HANDOFF;
            default -> Route.REVIEW;
        });
    }

    @Test
    void mandatorySignalsOverrideReviewSignalsAndFailuresOverrideOrdinaryReview() {
        var human = policy.decide(new Signals(Set.of(Signal.HUMAN_REQUEST, Signal.FINANCE_COMMITMENT), 0));
        assertThat(human.route()).isEqualTo(Route.MANDATORY_HANDOFF);
        assertThat(human.reason()).isEqualTo(Reason.HUMAN_REQUEST);
        var critical = policy.decide(new Signals(Set.of(Signal.SERIOUS_COMPLAINT, Signal.RESPONSE_REVIEW), 0));
        assertThat(critical.route()).isEqualTo(Route.MANDATORY_HANDOFF);
        assertThat(critical.reason()).isEqualTo(Reason.SERIOUS_COMPLAINT);
        assertThat(policy.decide(new Signals(Set.of(Signal.RESPONSE_REVIEW), 2)).reason())
                .isEqualTo(Reason.REPEATED_FAILURE);
    }

    @Test
    void absentEvidenceAloneDoesNotForceHandoff() {
        assertThat(policy.decide(new Signals(Set.of(Signal.RESPONSE_REVIEW), 1)).route()).isEqualTo(Route.REVIEW);
    }

    @Test
    void onlyConsecutiveSubstantiveFailuresEscalateAndSuccessfulAssistanceResetsTheInputCounter() {
        assertThat(policy.decide(new Signals(Set.of(), 1)).route()).isEqualTo(Route.AUTOMATIC);
        assertThat(policy.decide(new Signals(Set.of(), 2)).reason()).isEqualTo(Reason.REPEATED_FAILURE);
        assertThat(policy.decide(new Signals(Set.of(), 0)).route()).isEqualTo(Route.AUTOMATIC);
        assertThatIllegalArgumentException().isThrownBy(() -> new Signals(Set.of(), -1));
    }

    @Test
    void routineVerifiedCatalogQuestionsHaveNoCommitmentSignal() {
        var result = policy.decide(new Signals(Set.of(), 0));
        assertThat(result.route()).isEqualTo(Route.AUTOMATIC);
        assertThat(result.reason()).isEqualTo(Reason.VERIFIED_ROUTINE);
    }
}
