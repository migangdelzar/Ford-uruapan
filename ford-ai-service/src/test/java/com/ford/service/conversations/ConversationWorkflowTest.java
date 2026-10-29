package com.ford.service.conversations;

import com.ford.service.channels.api.InboundDebounceGate;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationTurnLeaseUnavailableException;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import com.ford.service.conversations.application.ConversationTurnState;
import com.ford.service.observability.AiObservationService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;

class ConversationWorkflowTest {

    @Test
    void defersTurnWhileDebounceWindowIsOpen() {
        var ids = new TurnIds();
        var gate = mock(InboundDebounceGate.class);
        when(gate.isDue(ids.tenantId, ids.conversationId, ids.receiptId)).thenReturn(false);
        var calls = new ArrayList<ConversationTurnReference>();
        var workflow = workflow(gate, calls, null);

        workflow.on(event(ids));

        assertThat(calls).isEmpty();
    }

    @Test
    void sendsDueTurnToCanonicalProcessorExactlyOnce() {
        var ids = new TurnIds();
        var gate = mock(InboundDebounceGate.class);
        when(gate.isDue(ids.tenantId, ids.conversationId, ids.receiptId)).thenReturn(true);
        var calls = new ArrayList<ConversationTurnReference>();
        var workflow = workflow(gate, calls, null);

        workflow.on(event(ids));

        assertThat(calls).containsExactly(new ConversationTurnReference(
                ids.tenantId, ids.conversationId, ids.receiptId));
    }

    @Test
    void persistsAcceptedProcessingAndDuplicateStatesInOrder() {
        var ids = new TurnIds();
        var ledger = mock(ConversationTurnLedgerPort.class);
        var calls = new ArrayList<ConversationTurnReference>();
        var workflow = workflow(null, calls, null, ledger, ConversationGraphRoute.DUPLICATE);

        workflow.on(event(ids));

        var order = inOrder(ledger);
        order.verify(ledger).recordOutcome(org.mockito.ArgumentMatchers.eq(ids.tenantId),
                org.mockito.ArgumentMatchers.eq(ids.conversationId), org.mockito.ArgumentMatchers.eq(ids.receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.1"), org.mockito.ArgumentMatchers.eq(ConversationTurnState.ACCEPTED),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        order.verify(ledger).recordOutcome(org.mockito.ArgumentMatchers.eq(ids.tenantId),
                org.mockito.ArgumentMatchers.eq(ids.conversationId), org.mockito.ArgumentMatchers.eq(ids.receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.1"), org.mockito.ArgumentMatchers.eq(ConversationTurnState.PROCESSING),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        order.verify(ledger).recordOutcome(org.mockito.ArgumentMatchers.eq(ids.tenantId),
                org.mockito.ArgumentMatchers.eq(ids.conversationId), org.mockito.ArgumentMatchers.eq(ids.receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.1"), org.mockito.ArgumentMatchers.eq(ConversationTurnState.DUPLICATE),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void releasedTurnBypassesDebounceAndUsesCanonicalProcessor() {
        var ids = new TurnIds();
        var gate = mock(InboundDebounceGate.class);
        var calls = new ArrayList<ConversationTurnReference>();
        var workflow = workflow(gate, calls, null);

        workflow.on(new InboundTurnReleased(ids.receiptId, ids.tenantId, ids.conversationId,
                Channel.WHATSAPP, "wamid.1", Instant.now()));

        verify(gate, never()).isDue(ids.tenantId, ids.conversationId, ids.receiptId);
        assertThat(calls).containsExactly(new ConversationTurnReference(
                ids.tenantId, ids.conversationId, ids.receiptId));
    }

    @Test
    void leaseContentionLeavesTurnRetryableWithoutInvokingProcessor() {
        var ids = new TurnIds();
        var calls = new ArrayList<ConversationTurnReference>();
        var lease = mock(ConversationTurnLeasePort.class);
        when(lease.tryAcquire(org.mockito.ArgumentMatchers.eq(ids.tenantId),
                org.mockito.ArgumentMatchers.eq(ids.conversationId), anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(15))))
                .thenReturn(false);
        var workflow = workflow(null, calls, lease);

        assertThatThrownBy(() -> workflow.on(event(ids)))
                .isInstanceOf(ConversationTurnLeaseUnavailableException.class);
        assertThat(calls).isEmpty();
        verify(lease, never()).release(org.mockito.ArgumentMatchers.eq(ids.tenantId),
                org.mockito.ArgumentMatchers.eq(ids.conversationId), anyString());
    }

    @Test
    void recordsAssistantUnavailableDispositionThroughGraphWrapper() {
        var ids = new TurnIds();
        var ledger = mock(ConversationTurnLedgerPort.class);
        var graph = new ConversationGraphRunner(reference -> {
            throw new AssistantTurnUnavailableException(AssistantTurnUnavailableException.Disposition.RETRY);
        });
        var workflow = new ConversationWorkflow(graph, mock(AiObservationService.class), null, null, ledger);

        assertThatThrownBy(() -> workflow.on(event(ids)))
                .isInstanceOf(RuntimeException.class);

        verify(ledger).recordOutcome(eq(ids.tenantId), eq(ids.conversationId), eq(ids.receiptId), eq("wamid.1"),
                eq(ConversationTurnState.RETRY_WAIT), eq("assistant_unavailable_retry"), any());
    }

    @Test
    void classifiesUnknownGraphFailureWithoutExposingExceptionMessage() {
        var ids = new TurnIds();
        var ledger = mock(ConversationTurnLedgerPort.class);
        var graph = new ConversationGraphRunner(reference -> {
            throw new IllegalStateException("synthetic-private-failure-text");
        });
        var workflow = new ConversationWorkflow(graph, mock(AiObservationService.class), null, null, ledger);

        assertThatThrownBy(() -> workflow.on(event(ids)))
                .hasMessageNotContaining("synthetic-private-failure-text");

        verify(ledger).recordOutcome(eq(ids.tenantId), eq(ids.conversationId), eq(ids.receiptId), eq("wamid.1"),
                eq(ConversationTurnState.RETRY_WAIT), eq("graph_failure"), any());
    }

    private static ConversationWorkflow workflow(InboundDebounceGate gate,
                                                 ArrayList<ConversationTurnReference> calls,
                                                 ConversationTurnLeasePort leases) {
        return workflow(gate, calls, leases, mock(ConversationTurnLedgerPort.class), ConversationGraphRoute.BOT_REPLY);
    }

    private static ConversationWorkflow workflow(InboundDebounceGate gate,
                                                 ArrayList<ConversationTurnReference> calls,
                                                 ConversationTurnLeasePort leases,
                                                 ConversationTurnLedgerPort ledger,
                                                 ConversationGraphRoute route) {
        var graph = new ConversationGraphRunner(reference -> {
            calls.add(reference);
            return new ConversationTurnOutcome(route);
        });
        return new ConversationWorkflow(graph, mock(AiObservationService.class), leases, gate, ledger);
    }

    private static InboundMessageAccepted event(TurnIds ids) {
        return new InboundMessageAccepted(ids.receiptId, ids.tenantId, ids.conversationId,
                Channel.WHATSAPP, "wamid.1", Instant.now());
    }

    private static final class TurnIds {
        private final UUID tenantId = UUID.randomUUID();
        private final UUID conversationId = UUID.randomUUID();
        private final UUID receiptId = UUID.randomUUID();
    }
}
