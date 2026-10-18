package com.ford.service.conversations;

import com.ford.service.channels.api.InboundDebounceGate;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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
        var repository = mock(ConversationRepository.class);
        var gate = mock(InboundDebounceGate.class);
        when(gate.isDue(tenantId, conversationId, receiptId)).thenReturn(false);

        workflow(repository, gate).on(event(receiptId, tenantId, conversationId));

        verifyNoInteractions(repository);
    }

    @Test
    void processesTheTurnOnceTheDebounceWindowHasElapsed() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var repository = mock(ConversationRepository.class);
        var gate = mock(InboundDebounceGate.class);
        when(gate.isDue(tenantId, conversationId, receiptId)).thenReturn(true);

        workflow(repository, gate).on(event(receiptId, tenantId, conversationId));

        verify(repository).find(conversationId);
    }

    @Test
    void releasedTurnBypassesTheDebounceGate() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var repository = mock(ConversationRepository.class);
        var gate = mock(InboundDebounceGate.class);

        workflow(repository, gate).on(new InboundTurnReleased(receiptId, tenantId, conversationId,
                Channel.WHATSAPP, "wamid.1", NOW));

        verify(gate, never()).isDue(any(), any(), any());
        verify(repository).find(conversationId);
    }

    private static InboundMessageAccepted event(UUID receiptId, UUID tenantId, UUID conversationId) {
        return new InboundMessageAccepted(receiptId, tenantId, conversationId, Channel.WHATSAPP, "wamid.1", NOW);
    }

    private static ConversationWorkflow workflow(ConversationRepository repository, InboundDebounceGate gate) {
        return new ConversationWorkflow(repository, mock(ConversationAssistant.class),
                mock(ApplicationEventPublisher.class), new HumanHandoffPolicy(true),
                mock(ConversationMemoryService.class), (id, messageId, decision) -> { },
                new DefaultAiObservationService(ObservationRegistry.NOOP), null, null, gate);
    }
}
