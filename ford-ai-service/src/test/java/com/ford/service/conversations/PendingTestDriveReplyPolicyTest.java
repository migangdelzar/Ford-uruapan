package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.conversations.application.PendingTestDriveReplyPolicy;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.leads.domain.LeadJourneyTopic;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PendingTestDriveReplyPolicyTest {

    private final PendingTestDriveReplyPolicy policy = new PendingTestDriveReplyPolicy();
    private final ConversationMemoryKey key = new ConversationMemoryKey(
            UUID.randomUUID(), Channel.WHATSAPP, "account", "participant", UUID.randomUUID());

    @Test
    void continuesAnUnambiguousTestDriveOfferWithOneNextQuestionAndJourneyProposal() {
        var memory = memory("Bronco Base 2026. ¿Le gustaría agendar una prueba de manejo?");

        var reply = policy.replyFor("Sí, me gustaría", memory).orElseThrow();

        assertThat(reply.text()).contains("¿Qué día");
        assertThat(reply.text().chars().filter(character -> character == '¿').count()).isEqualTo(1);
        assertThat(reply.decision().intent()).isEqualTo(ConversationIntent.APPOINTMENT);
        assertThat(reply.decision().toolFamily()).isEqualTo(ToolFamily.APPOINTMENT);
        assertThat(reply.decision().evidence()).allSatisfy(evidence ->
                assertThat(evidence.fallbackUsed()).isTrue());
        assertThat(reply.journeyProposal().currentTopic())
                .isEqualTo(LeadJourneyTopic.TEST_DRIVE_APPOINTMENT);
        assertThat(reply.journeyProposal().pendingQuestionKey()).isEqualTo("TEST_DRIVE_DATE");
    }

    @Test
    void leavesAmbiguousMenuOfferToNormalConversationHandling() {
        var memory = memory("¿Le gustaría agendar una prueba de manejo o revisar el financiamiento?");

        assertThat(policy.replyFor("Sí, me gustaría", memory)).isEmpty();
    }

    @Test
    void leavesUnclearRepliesToNormalConversationHandling() {
        var memory = memory("¿Le gustaría agendar una prueba de manejo?");

        assertThat(policy.replyFor("Tal vez después", memory)).isEmpty();
    }

    @Test
    void usesStructuredPendingConfirmationEvenWhenRecentMemoryIsEmpty() {
        var journey = new LeadJourneySnapshot(key.tenantId(), key.conversationId(), null,
                LeadJourneyTopic.TEST_DRIVE_APPOINTMENT, Set.of(LeadJourneyTopic.TEST_DRIVE_APPOINTMENT),
                Set.of(), Map.of(), "TEST_DRIVE_CONFIRMATION", 0, null, 2, Instant.now());

        var reply = policy.replyFor("Sí, me gustaría", null, journey).orElseThrow();

        assertThat(reply.text()).contains("¿Qué día");
        assertThat(reply.decision().intent()).isEqualTo(ConversationIntent.APPOINTMENT);
    }

    private ConversationMemoryContext memory(String lastAssistantReply) {
        return new ConversationMemoryContext(key, "", List.of(new ConversationMessage(
                UUID.randomUUID(), key.conversationId(), ConversationMessage.Direction.OUTBOUND,
                "assistant-1", lastAssistantReply, Instant.now())), List.of(), 0);
    }
}
