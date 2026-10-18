package com.ford.service.conversations;

import com.ford.service.ai.application.InboundContentResolutionService;
import com.ford.service.ai.application.InboundContentStatus;
import com.ford.service.ai.application.ResolvedInboundContent;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.DefaultConversationTurnProcessor;
import com.ford.service.conversations.application.ShadowReviewGate;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ShadowDraftRecorder;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShadowReviewTurnInterceptionTest {

    private record Fixture(ConversationTurnReference reference, AcceptedInboundMessage inbound,
                           ConversationSnapshot snapshot, ConversationMemoryContext memoryContext,
                           LeadJourneySnapshot journey, AcceptedInboundMessageRepository inboundRepository,
                           ConversationRepository conversationRepository, ConversationMemoryService memory,
                           LeadJourneyService leadJourney, ConversationAssistant assistant,
                           ConversationTurnCommitService commit, InboundContentResolutionService resolver,
                           AssistantReply reply) {
    }

    @Test
    void shadowModeRecordsBoundedDraftAndSkipsSendAndOutbox() {
        var fx = fixture();
        var recorder = mock(ShadowDraftRecorder.class);
        when(fx.assistant().respond(any())).thenReturn(fx.reply());
        when(fx.commit().commit(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = processor(fx, ShadowReviewGate.of(true, false), recorder);

        assertThat(processor.process(fx.reference()).route()).isEqualTo(ConversationGraphRoute.IGNORED);

        verify(recorder).record(eq(fx.reference().tenantId()), eq(fx.reference().conversationId()),
                eq(fx.reference().receiptId()), eq(fx.inbound().providerMessageId()),
                eq(fx.reply().text()), any(Instant.class));
        verify(fx.commit(), never()).commit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void killSwitchStopsBotSendAndKeepsExistingData() {
        var fx = fixture();
        var recorder = mock(ShadowDraftRecorder.class);

        var processor = processor(fx, ShadowReviewGate.of(false, true), recorder);

        when(fx.commit().forceHumanHandoff(any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.HUMAN_PENDING);

        assertThat(processor.process(fx.reference()).route()).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);

        verifyNoInteractions(fx.assistant());
        verify(fx.commit(), never()).commit(any(), any(), any(), any(), any(), any());
        verify(fx.commit()).forceHumanHandoff(fx.reference(), fx.inbound(), fx.snapshot(), fx.memoryContext(), fx.journey());
        verifyNoInteractions(recorder);
        verify(fx.conversationRepository()).find(fx.reference().tenantId(), fx.reference().conversationId());
    }

    @Test
    void shadowDisabledStillSendsThroughExistingPipeline() {
        var fx = fixture();
        var recorder = mock(ShadowDraftRecorder.class);
        when(fx.assistant().respond(any())).thenReturn(fx.reply());
        when(fx.commit().commit(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConversationGraphRoute.BOT_REPLY);

        var processor = processor(fx, ShadowReviewGate.disabled(), recorder);

        assertThat(processor.process(fx.reference()).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        verify(fx.commit()).commit(fx.reference(), fx.inbound(), fx.snapshot(), fx.memoryContext(),
                fx.journey(), fx.reply());
        verifyNoInteractions(recorder);
    }

    private static Fixture fixture() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.shadow.1", "customer-1", "¿Promociones?", Instant.now());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());
        var memoryContext = ConversationMemoryContext.empty(
                new ConversationMemoryKey(tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId));
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var leadJourney = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var reply = new AssistantReply("Sí, te comparto las promociones vigentes.", false);
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.shadow.1")).thenReturn(false);
        when(memory.load(any())).thenReturn(memoryContext);
        when(leadJourney.load(tenantId, conversationId)).thenReturn(journey);
        return new Fixture(reference, inbound, snapshot, memoryContext, journey, inboundRepository,
                conversationRepository, memory, leadJourney, assistant, commit, resolver, reply);
    }

    private static ConversationTurnProcessor processor(Fixture fx, ShadowReviewGate gate, ShadowDraftRecorder recorder) {
        return new DefaultConversationTurnProcessor(fx.inboundRepository(), fx.conversationRepository(),
                fx.memory(), fx.leadJourney(), fx.assistant(), fx.commit(), fx.resolver(), recorder, gate);
    }
}
