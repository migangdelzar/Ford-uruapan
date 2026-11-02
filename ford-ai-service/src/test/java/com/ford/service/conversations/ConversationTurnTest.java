package com.ford.service.conversations;

import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.conversations.domain.ConversationTurn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTurnTest {
    @Test
    void legacyTextTurnDefaultsToNoReplyOptions() {
        assertThat(new ConversationTurn("Hola").replyOptions()).isEmpty();
    }

    @Test
    void copiesReplyOptionsImmutably() {
        var options = new ArrayList<>(List.of(new ReplyOption("vehicle-0123456789abcdef", "Ranger 2027 XLT")));
        var turn = new ConversationTurn("Elija una versión", options);
        options.clear();

        assertThat(turn.replyOptions()).containsExactly(new ReplyOption("vehicle-0123456789abcdef", "Ranger 2027 XLT"));
        assertThatThrownBy(() -> turn.replyOptions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
