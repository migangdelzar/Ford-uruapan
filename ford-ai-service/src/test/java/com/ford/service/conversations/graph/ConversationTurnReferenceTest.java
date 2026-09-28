package com.ford.service.conversations.graph;

import com.ford.service.conversations.application.graph.ConversationTurnReference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTurnReferenceTest {

    @Test
    void usesOneCheckpointThreadPerReceiptSoNewTurnsDoNotResumeATerminalGraph() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var first = new ConversationTurnReference(tenant, conversation, UUID.randomUUID());
        var second = new ConversationTurnReference(tenant, conversation, UUID.randomUUID());

        assertThat(first.checkpointThreadId()).isNotEqualTo(second.checkpointThreadId());
        assertThat(first.checkpointThreadId()).isEqualTo(tenant + ":" + conversation + ":" + first.receiptId());
        assertThat(second.checkpointThreadId()).isEqualTo(tenant + ":" + conversation + ":" + second.receiptId());
    }
}
