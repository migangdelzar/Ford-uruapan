package com.ford.service.conversations;

import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.DefaultConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultConversationTurnProcessorTest {

    @Test
    void loadsExactTenantScopedReceiptAndCallsExistingAssistantOnce() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.1", "customer-1", "¿Promociones?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var leadJourney = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var memoryContext = ConversationMemoryContext.empty(
                new com.ford.service.conversations.domain.ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                        "phone-1", "customer-1", conversationId));
        var reply = new AssistantReply("Sí, te comparto las promociones vigentes.", false);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.1")).thenReturn(false);
        when(memory.load(any())).thenReturn(memoryContext);
        when(leadJourney.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenReturn(reply);
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                leadJourney, assistant, commit);

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        verify(inboundRepository).find(tenantId, conversationId, receiptId);
        verify(memory).recordInbound(any(), org.mockito.ArgumentMatchers.eq(receiptId),
                org.mockito.ArgumentMatchers.eq("wamid.1"), org.mockito.ArgumentMatchers.eq("¿Promociones?"),
                org.mockito.ArgumentMatchers.eq(inbound.receivedAt()));
        verify(assistant).respond(any());
        verify(commit).commit(reference, inbound, snapshot, memoryContext, journey, reply);
    }

    @Test
    void duplicateTurnReturnsWithoutCallingModelAgain() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var assistant = mock(ConversationAssistant.class);
        when(inboundRepository.find(tenantId, conversationId, reference.receiptId()))
                .thenReturn(Optional.of(new AcceptedInboundMessage("wamid.duplicate", "customer-1", "Hola", Instant.now())));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.duplicate"))
                .thenReturn(true);

        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                mock(ConversationMemoryService.class), mock(LeadJourneyService.class), assistant,
                mock(ConversationTurnCommitService.class));

        assertThat(processor.process(reference).route()).isEqualTo(ConversationGraphRoute.IGNORED);
        verify(assistant, never()).respond(any());
    }

    @Test
    void assistantFailureDoesNotCommitJourneyOrOutboundEffects() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.failure", "customer-1", "Quiero una Ranger", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var journeyService = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var key = new com.ford.service.conversations.domain.ConversationMemoryKey(
                tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId);
        var memoryContext = ConversationMemoryContext.empty(key);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, inbound.providerMessageId()))
                .thenReturn(false);
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(memory.load(any())).thenReturn(memoryContext);
        when(journeyService.load(tenantId, conversationId)).thenReturn(journey);
        when(assistant.respond(any())).thenThrow(new IllegalStateException("model unavailable"));
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository,
                memory, journeyService, assistant, commit);

        assertThatThrownBy(() -> processor.process(reference)).isInstanceOf(IllegalStateException.class);

        verify(commit, never()).commit(any(), any(), any(), any(), any(), any());
    }
}
