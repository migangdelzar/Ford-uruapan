package com.ford.service.conversations;

import com.ford.service.conversations.domain.ConversationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ConversationStateTransitionTest {

    @Test
    void supportsCaptureAndHumanTakeoverTransitions() {
        var state = ConversationState.BOT_ACTIVE
                .transitionTo(ConversationState.LEAD_CAPTURE)
                .transitionTo(ConversationState.HUMAN_PENDING)
                .transitionTo(ConversationState.HUMAN_ACTIVE)
                .transitionTo(ConversationState.CLOSED);

        assertThat(state).isEqualTo(ConversationState.CLOSED);
    }

    @Test
    void doesNotAllowBotToResumeAClosedConversationWithoutPolicy() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ConversationState.CLOSED.transitionTo(ConversationState.BOT_ACTIVE));
    }
}
