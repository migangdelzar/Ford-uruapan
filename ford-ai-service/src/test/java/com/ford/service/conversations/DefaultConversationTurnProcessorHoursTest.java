package com.ford.service.conversations;

import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.domain.Channel;
import com.ford.service.ai.application.InboundContentResolutionService;
import com.ford.service.ai.application.InboundContentStatus;
import com.ford.service.ai.application.ResolvedInboundContent;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.DealershipResponsePlanner;
import com.ford.service.conversations.application.DealershipHoursPolicy;
import com.ford.service.conversations.application.DefaultConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConversationTurnProcessorHoursTest {
    private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
    private static final Instant MONDAY_AFTER_HOURS = Instant.parse("2026-10-20T02:00:00Z");

    @Test
    void afterHoursCatalogQuestionGetsNoUnrequestedHoursNotice() {
        var fixture = fixture(MONDAY_AFTER_HOURS);
        var reply = new AssistantReply("Sí, la Ranger 2026 tiene precio publicado.", false);
        when(fixture.assistant.respond(any())).thenReturn(reply);

        fixture.processor.process(fixture.reference);

        var captured = ArgumentCaptor.forClass(AssistantReply.class);
        verify(fixture.commit).commit(any(), any(), any(), any(), any(), captured.capture());
        assertThat(captured.getValue().text())
                .contains("Sí, la Ranger 2026 tiene precio publicado.")
                .doesNotContain(DealershipResponsePlanner.AFTER_HOURS_NOTICE, "horario festivo")
                .doesNotContainIgnoringCase("minuto");
    }

    @Test
    void duringHoursReplyIsNotChanged() {
        // 2026-10-19 is Monday 10:00 local -> open.
        var fixture = fixture(Instant.parse("2026-10-19T16:00:00Z"));
        var reply = new AssistantReply("Con gusto, ¿qué modelo le interesa?", false);
        when(fixture.assistant.respond(any())).thenReturn(reply);

        fixture.processor.process(fixture.reference);

        var captured = ArgumentCaptor.forClass(AssistantReply.class);
        verify(fixture.commit).commit(any(), any(), any(), any(), any(), captured.capture());
        assertThat(captured.getValue()).isSameAs(reply);
    }

    private static Fixture fixture(Instant now) {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var inbound = new AcceptedInboundMessage("wamid.1", "customer-1", "¿Cuál es el precio de la Ranger?",
                now);
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP, "phone-1",
                "customer-1", ConversationState.BOT_ACTIVE, 2, null, null);
        var journey = new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                null, null, 0, null, 0, now);
        var inboundRepository = mock(AcceptedInboundMessageRepository.class);
        var conversationRepository = mock(ConversationRepository.class);
        var memory = mock(ConversationMemoryService.class);
        var leadJourney = mock(LeadJourneyService.class);
        var assistant = mock(ConversationAssistant.class);
        var commit = mock(ConversationTurnCommitService.class);
        var resolver = mock(InboundContentResolutionService.class);
        var memoryContext = ConversationMemoryContext.empty(
                new ConversationMemoryKey(tenantId, Channel.WHATSAPP, "phone-1", "customer-1", conversationId));
        when(inboundRepository.find(tenantId, conversationId, receiptId)).thenReturn(Optional.of(inbound));
        when(resolver.resolve(inbound, tenantId, conversationId)).thenReturn(
                new ResolvedInboundContent(inbound.text(), InboundContentStatus.READY));
        when(conversationRepository.find(tenantId, conversationId)).thenReturn(Optional.of(snapshot));
        when(conversationRepository.turnAlreadyCompleted(tenantId, conversationId, "wamid.1")).thenReturn(false);
        when(memory.load(any())).thenReturn(memoryContext);
        when(leadJourney.load(tenantId, conversationId)).thenReturn(journey);
        when(commit.commit(any(), any(), any(), any(), any(), any())).thenReturn(ConversationGraphRoute.BOT_REPLY);

        var policy = new DealershipHoursPolicy(Clock.fixed(now, ZONE), ZONE,
                Map.of(DayOfWeek.MONDAY, new com.ford.service.conversations.application.DealershipHours(
                                LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.TUESDAY, new com.ford.service.conversations.application.DealershipHours(
                                LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.WEDNESDAY, new com.ford.service.conversations.application.DealershipHours(
                                LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.THURSDAY, new com.ford.service.conversations.application.DealershipHours(
                                LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.FRIDAY, new com.ford.service.conversations.application.DealershipHours(
                                LocalTime.of(9, 0), LocalTime.of(19, 0)),
                        DayOfWeek.SATURDAY, new com.ford.service.conversations.application.DealershipHours(
                                LocalTime.of(9, 0), LocalTime.of(14, 0))),
                Map.of());
        var processor = new DefaultConversationTurnProcessor(inboundRepository, conversationRepository, memory,
                leadJourney, assistant, commit, resolver, new DealershipResponsePlanner(policy));
        return new Fixture(reference, assistant, commit, processor);
    }

    private record Fixture(ConversationTurnReference reference, ConversationAssistant assistant,
                           ConversationTurnCommitService commit, DefaultConversationTurnProcessor processor) { }
}
