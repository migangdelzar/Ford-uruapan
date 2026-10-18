package com.ford.service.conversations;

import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.application.ConversationDecisionRepository;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.shared.HumanHandoffPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnCommitServiceTest {

    @Test
    void complaintCreatesPartialHandoffWithoutDependingOnModelDecision() {
        var fixture = handoffFixture("Estoy muy molesta por la atención que recibí.", List.of(), true);

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Lamento lo ocurrido.", false));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), any(), any());
    }

    @Test
    void secondRefusalToProvideQualificationDetailsCreatesPartialHandoff() {
        var prior = message(UUID.randomUUID(), ConversationMessage.Direction.INBOUND,
                "in-previous", "No quiero compartir mi nombre.");
        var fixture = handoffFixture("No quiero compartir mi nombre.", List.of(prior), true);

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Entiendo.", false));

        assertThat(route).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), any(), any());
    }

    @Test
    void handoffConfirmationNamesFordServiceQueueWithoutEta() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), true);

        fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Claro.", false));

        var confirmation = org.mockito.ArgumentCaptor.forClass(ConversationTurn.class);
        verify(fixture.repository).requestHumanHandoff(eq(fixture.tenantId), eq(fixture.conversationId),
                eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()), any(), confirmation.capture());
        assertThat(confirmation.getValue().text()).contains("Ford Service");
        assertThat(confirmation.getValue().text().toLowerCase())
                .doesNotContain("cuanto antes", "en breve", "en minutos", "notificad", "le contactar", "le llamar");
    }

    @Test
    void rejectedHandoffEmitsNoCustomerCopyAndNoEvent() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), false);

        var route = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory,
                new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), new AssistantReply("Claro.", false));

        assertThat(route).isEqualTo(ConversationGraphRoute.IGNORED);
        assertThat(fixture.events.events).isEmpty();
        verify(fixture.repository, org.mockito.Mockito.never())
                .completeBotTurn(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void retriedHandoffReusesIdempotencyKeyAndDoesNotDuplicateEvent() {
        var fixture = handoffFixture("Quiero hablar con un asesor.", List.of(), true);
        var journey = new LeadJourneySnapshot(fixture.tenantId, fixture.conversationId, null, null, null, null,
                null, null, 0, null, 0, Instant.now());

        fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory, journey,
                new AssistantReply("Claro.", false));
        org.mockito.Mockito.when(fixture.repository.requestHumanHandoff(eq(fixture.tenantId),
                eq(fixture.conversationId), eq(fixture.snapshot.version()), eq(fixture.inbound.providerMessageId()),
                any(), any())).thenReturn(false);
        var retry = fixture.service.commit(fixture.reference, fixture.inbound, fixture.snapshot, fixture.memory, journey,
                new AssistantReply("Claro.", false));

        assertThat(retry).isEqualTo(ConversationGraphRoute.IGNORED);
        assertThat(fixture.events.events)
                .filteredOn(com.ford.service.conversations.api.HandoffRequested.class::isInstance).hasSize(1);
    }

    private static HandoffFixture handoffFixture(String currentText, List<ConversationMessage> priorTurns,
                                                 boolean handoffAccepted) {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var inbound = new AcceptedInboundMessage("in-current", "customer", currentText, Instant.now());
        var reference = new ConversationTurnReference(tenantId, conversationId, UUID.randomUUID());
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "account", "customer", ConversationState.BOT_ACTIVE, 3, currentText, inbound.providerMessageId());
        var key = new ConversationMemoryKey(tenantId, Channel.WHATSAPP, "account", "customer", conversationId);
        var memory = new ConversationMemoryContext(key, "", priorTurns, List.of(), 0);
        var repository = mock(ConversationRepository.class);
        when(repository.requestHumanHandoff(eq(tenantId), eq(conversationId), eq(snapshot.version()),
                eq(inbound.providerMessageId()), any(), any())).thenReturn(handoffAccepted);
        var events = new RecordingPublisher();
        var service = new ConversationTurnCommitService(repository, (id, messageId, decision) -> { },
                mock(LeadJourneyService.class), new HumanHandoffPolicy(true), mock(ConversationMemoryService.class),
                events);
        return new HandoffFixture(tenantId, conversationId, inbound, reference, snapshot, memory, repository,
                service, events);
    }

    private record HandoffFixture(UUID tenantId, UUID conversationId, AcceptedInboundMessage inbound,
                                  ConversationTurnReference reference, ConversationSnapshot snapshot,
                                  ConversationMemoryContext memory, ConversationRepository repository,
                                  ConversationTurnCommitService service, RecordingPublisher events) { }

    @Test
    void compactionFailureDoesNotPreventReplyCommitOrChangeCanonicalTranscript() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var inbound = new AcceptedInboundMessage("wamid.current", "customer-1", "¿Cuánto queda por mes?",
                Instant.now());
        var reference = new ConversationTurnReference(tenantId, conversationId, receiptId);
        var snapshot = new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "provider-account", "customer-1", ConversationState.BOT_ACTIVE, 3, inbound.text(),
                inbound.providerMessageId());
        var key = new ConversationMemoryKey(tenantId, Channel.WHATSAPP,
                "provider-account", "customer-1", conversationId);
        var memory = new ConversationMemoryContext(key, "último resumen válido", recentWindow(conversationId),
                List.of(), 7);
        var canonicalTranscript = new ArrayList<>(memory.recentMessages());
        canonicalTranscript.add(message(conversationId, ConversationMessage.Direction.INBOUND,
                inbound.providerMessageId(), inbound.text()));
        var originalTranscript = List.copyOf(canonicalTranscript);
        var repository = new RecordingRepository(snapshot, canonicalTranscript);
        var events = new RecordingPublisher();
        var properties = new MemoryProperties();
        properties.setPromptRecentMessageLimit(8);
        var memoryService = new ConversationMemoryService(new NoOpMemoryPort(), properties,
                new com.ford.service.conversations.application.NoOpConversationMemorySemanticPort(),
                new com.ford.service.conversations.application.NoOpConversationMemoryRecallPort(),
                event -> { },
                (tenant, conversation, summary, expired, maxChars) -> {
                    throw new IllegalStateException("provider unavailable");
                });
        var service = new ConversationTurnCommitService(repository, (id, messageId, decision) -> { },
                org.mockito.Mockito.mock(LeadJourneyService.class), new HumanHandoffPolicy(false),
                memoryService, events);
        var reply = new AssistantReply("Puedo ayudarle a revisar un pago mensual estimado.", false);

        var route = service.commit(reference, inbound, snapshot, memory,
                new LeadJourneySnapshot(tenantId, conversationId, null, null, null, null,
                        null, null, 0, null, 0, Instant.now()), reply);

        assertThat(route).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        assertThat(repository.completedTurn).isEqualTo(new ConversationTurn(reply.text()));
        assertThat(repository.canonicalTranscript).hasSize(originalTranscript.size() + 1);
        assertThat(repository.canonicalTranscript.subList(0, originalTranscript.size()))
                .containsExactlyElementsOf(originalTranscript);
        assertThat(repository.canonicalTranscript).last().satisfies(message -> {
            assertThat(message.direction()).isEqualTo(ConversationMessage.Direction.OUTBOUND);
            assertThat(message.content()).isEqualTo(reply.text());
        });
        assertThat(events.events).anySatisfy(event ->
                assertThat(event).isInstanceOfSatisfying(ConversationMemoryProjectionRefreshRequested.class,
                        projection -> assertThat(projection.update().summary())
                                .isEqualTo("último resumen válido")));
    }

    private static List<ConversationMessage> recentWindow(UUID conversationId) {
        var messages = new ArrayList<ConversationMessage>();
        for (var index = 0; index < 4; index++) {
            messages.add(message(conversationId, ConversationMessage.Direction.INBOUND,
                    "inbound-" + index, "Mensaje del cliente " + index));
            messages.add(message(conversationId, ConversationMessage.Direction.OUTBOUND,
                    "outbound-" + index, "Respuesta previa " + index));
        }
        return List.copyOf(messages);
    }

    private static ConversationMessage message(UUID conversationId, ConversationMessage.Direction direction,
                                               String providerMessageId, String content) {
        return new ConversationMessage(UUID.randomUUID(), conversationId, direction,
                providerMessageId, content, Instant.now());
    }

    private static final class NoOpMemoryPort implements ConversationMemoryPort {
        @Override
        public ConversationMemoryContext load(com.ford.service.conversations.domain.MemoryQuery query) {
            return ConversationMemoryContext.empty(query.key());
        }

        @Override
        public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
        }

        @Override
        public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                    MemoryProjectionUpdate update,
                                                    long expectedVersion) {
            return new MemoryUpdateResult(true, expectedVersion + 1);
        }
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    private static final class RecordingRepository implements ConversationRepository {
        private final ConversationSnapshot snapshot;
        private final List<ConversationMessage> canonicalTranscript;
        private ConversationTurn completedTurn;

        private RecordingRepository(ConversationSnapshot snapshot,
                                    List<ConversationMessage> canonicalTranscript) {
            this.snapshot = snapshot;
            this.canonicalTranscript = canonicalTranscript;
        }

        @Override
        public Optional<ConversationSnapshot> find(UUID conversationId) {
            return Optional.of(snapshot);
        }

        @Override
        public boolean completeBotTurn(UUID tenantId, UUID conversationId, long expectedVersion,
                                       String inboundProviderMessageId, ConversationTurn turn) {
            completedTurn = turn;
            canonicalTranscript.add(message(conversationId, ConversationMessage.Direction.OUTBOUND,
                    "outbound:" + inboundProviderMessageId, turn.text()));
            return true;
        }

        @Override
        public boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConversationState state(UUID conversationId) {
            return snapshot.state();
        }

        @Override
        public boolean recordMessageIfNew(UUID conversationId, String providerMessageId) {
            return true;
        }

        @Override
        public void transition(UUID conversationId, ConversationState expected, ConversationState target) {
            throw new UnsupportedOperationException();
        }
    }
}
