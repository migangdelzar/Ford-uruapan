package com.ford.service.conversations;

import com.ford.service.channels.api.InboundDebounceGate;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.application.graph.ConversationGraphResult;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import com.ford.service.observability.AiObservationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationWorkflowDebounceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");

    @Test
    void defersTheTurnWhileTheDebounceWindowIsStillOpen() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var graphRunner = mock(ConversationGraphRunner.class);
        var gate = mock(InboundDebounceGate.class);
        when(gate.isDue(tenantId, conversationId, receiptId)).thenReturn(false);

        workflow(graphRunner, gate).on(event(receiptId, tenantId, conversationId));

        verifyNoInteractions(graphRunner);
    }

    @Test
    void processesTheTurnOnceTheDebounceWindowHasElapsed() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var graphRunner = mock(ConversationGraphRunner.class);
        var gate = mock(InboundDebounceGate.class);
        when(gate.isDue(tenantId, conversationId, receiptId)).thenReturn(true);
        when(graphRunner.run(any())).thenAnswer(invocation -> new ConversationGraphResult(
                invocation.getArgument(0), ConversationGraphRoute.BOT_REPLY, true));

        workflow(graphRunner, gate).on(event(receiptId, tenantId, conversationId));

        verify(graphRunner).run(any());
    }

    @Test
    void releasedTurnBypassesTheDebounceGate() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var graphRunner = mock(ConversationGraphRunner.class);
        var gate = mock(InboundDebounceGate.class);
        when(graphRunner.run(any())).thenAnswer(invocation -> new ConversationGraphResult(
                invocation.getArgument(0), ConversationGraphRoute.BOT_REPLY, true));

        workflow(graphRunner, gate).on(new InboundTurnReleased(receiptId, tenantId, conversationId,
                Channel.WHATSAPP, "wamid.1", NOW));

        verify(gate, never()).isDue(any(), any(), any());
        verify(graphRunner).run(any());
    }

    private static InboundMessageAccepted event(UUID receiptId, UUID tenantId, UUID conversationId) {
        return new InboundMessageAccepted(receiptId, tenantId, conversationId, Channel.WHATSAPP, "wamid.1", NOW);
    }

    private static ConversationWorkflow workflow(ConversationGraphRunner graphRunner, InboundDebounceGate gate) {
        return new ConversationWorkflow(graphRunner, mock(AiObservationService.class), null, gate,
                mock(ConversationTurnLedgerPort.class));
    }
}
