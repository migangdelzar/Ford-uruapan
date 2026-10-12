package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.application.DefaultConversationHandoffMessageService;
import com.ford.service.conversations.application.port.out.OperatorConversationCommandRepository;
import com.ford.service.conversations.domain.ConversationState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConversationHandoffMessageServiceTest {

    @Test
    void sendsOneCustomerMessageAuthoredByTheAdvisorAfterTakeover() {
        var commands = mock(OperatorConversationCommandRepository.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var operatorId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        when(commands.messageExists(tenantId, conversationId, "operator:handoff:" + ticketId)).thenReturn(false);
        when(commands.find(tenantId, conversationId)).thenReturn(Optional.of(
                new OperatorConversationCommandRepository.CommandTarget(tenantId, conversationId,
                        Channel.WHATSAPP, "customer-1", ConversationState.HUMAN_ACTIVE, 7)));
        when(commands.appendMessage(any())).thenAnswer(invocation -> {
            var message = invocation.getArgument(0, OperatorConversationCommandRepository.CommandMessage.class);
            return new CommandReceipt(message.messageId(), conversationId, true, "QUEUED", 8, false, Instant.now());
        });

        new DefaultConversationHandoffMessageService(commands)
                .sendAdvisorTakeoverMessage(tenantId, conversationId, operatorId, ticketId);

        var sent = ArgumentCaptor.forClass(OperatorConversationCommandRepository.CommandMessage.class);
        verify(commands).appendMessage(sent.capture());
        assertThat(sent.getValue().operatorId()).isEqualTo(operatorId);
        assertThat(sent.getValue().providerMessageId()).isEqualTo("operator:handoff:" + ticketId);
        assertThat(sent.getValue().content()).isEqualTo("Hola, ya tomé tu conversación. Te responderé por aquí en cuanto pueda.");
        assertThat(sent.getValue().internalNote()).isFalse();
        assertThat(sent.getValue().expectedVersion()).isEqualTo(7);
    }

    @Test
    void doesNotQueueTheSameTakeoverMessageTwice() {
        var commands = mock(OperatorConversationCommandRepository.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        when(commands.messageExists(tenantId, conversationId, "operator:handoff:" + ticketId)).thenReturn(true);

        new DefaultConversationHandoffMessageService(commands)
                .sendAdvisorTakeoverMessage(tenantId, conversationId, UUID.randomUUID(), ticketId);

        verify(commands, never()).appendMessage(any());
    }
}
