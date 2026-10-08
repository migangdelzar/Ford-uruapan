package com.ford.service.conversations;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.domain.Channel;
import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.conversations.application.ConversationWorkflow;
import com.ford.service.conversations.application.ConversationTurnLeaseUnavailableException;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.ConversationDecisionRepository;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.observability.AiObservationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationWorkflowTest {

    @Test
    void leavesInboundEventIncompleteWhenConversationLeaseIsBusy() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var processorCalls = new ArrayList<ConversationTurnReference>();
        var requestedLeaseDuration = new java.util.concurrent.atomic.AtomicReference<java.time.Duration>();
        var graph = new ConversationGraphRunner(reference -> {
            processorCalls.add(reference);
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        });
        ConversationTurnLeasePort busyLease = new ConversationTurnLeasePort() {
            @Override
            public boolean tryAcquire(UUID tenant, UUID conversation, String owner, java.time.Duration duration) {
                requestedLeaseDuration.set(duration);
                return false;
            }

            @Override
            public void release(UUID tenant, UUID conversation, String owner) {
                throw new AssertionError("must not release a lease that was not acquired");
            }
        };
        var workflow = new ConversationWorkflow(graph, mock(AiObservationService.class), busyLease);

        assertThatThrownBy(() -> workflow.on(new InboundMessageAccepted(receiptId, tenantId, conversationId,
                Channel.WHATSAPP, "wamid.lease-busy", Instant.now())))
                .isInstanceOf(ConversationTurnLeaseUnavailableException.class);
        assertThat(processorCalls).isEmpty();
        assertThat(requestedLeaseDuration.get()).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    void productionWorkflowDelegatesInboundIdsToTheBoundedGraph() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var receiptId = UUID.randomUUID();
        var processorCalls = new ArrayList<ConversationTurnReference>();
        var graph = new ConversationGraphRunner(reference -> {
            processorCalls.add(reference);
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        });
        var workflow = new ConversationWorkflow(graph, mock(AiObservationService.class));

        workflow.on(new InboundMessageAccepted(receiptId, tenantId, conversationId, Channel.WHATSAPP,
                "wamid.graph", Instant.now()));

        assertThat(processorCalls).containsExactly(new ConversationTurnReference(tenantId, conversationId, receiptId));
    }

    @Test
    void activeBotTurnPersistsReplyAndPublishesDeliveryRequest() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 4));
        var assistant = new RecordingAssistant(new AssistantReply(
                "La Ranger está disponible.", false));
        var publisher = new RecordingPublisher();
        var workflow = new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(true), memoryService());

        workflow.on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                "wamid.1", Instant.parse("2026-09-19T12:00:00Z")));

        assertThat(assistant.requests).hasSize(1);
        assertThat(repository.completedTurn).isEqualTo(new ConversationTurn("La Ranger está disponible."));
        assertThat(repository.completedExpectedVersion).isEqualTo(4);
        assertThat(publisher.events).anySatisfy(event ->
                assertThat(event).isInstanceOfSatisfying(ConversationMemoryProjectionRefreshRequested.class,
                        projection -> {
                            assertThat(projection.key().tenantId()).isEqualTo(tenantId);
                            assertThat(projection.key().conversationId()).isEqualTo(conversationId);
                            assertThat(projection.expectedVersion()).isZero();
                        }));
        assertThat(publisher.events).noneMatch(OutboundDeliveryRequested.class::isInstance);
    }

    @Test
    void doesNotPublishSecondDeliveryRequestWhenRepositoryOwnsAtomicOutboxWrite() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 1)),
                new RecordingAssistant(new AssistantReply("Una respuesta", false)), publisher,
                new HumanHandoffPolicy(false), memoryService())
                .on(new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId,
                        Channel.WHATSAPP, "wamid.atomic-outbox", Instant.now()));

        assertThat(publisher.events).noneMatch(OutboundDeliveryRequested.class::isInstance);
    }

    @Test
    void recordsOnlyBoundedChannelAndOutcomeForInboundProcessing() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var observations = mock(AiObservationService.class);
        var workflow = new ConversationWorkflow(
                new RecordingConversationRepository(snapshot(tenantId, conversationId,
                        ConversationState.BOT_ACTIVE, 1)),
                new RecordingAssistant(new AssistantReply("Respuesta útil.", false)),
                new RecordingPublisher(), new HumanHandoffPolicy(false), memoryService(),
                (id, messageId, decision) -> { }, observations);

        workflow.on(new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                "wamid.private", Instant.now()));

        verify(observations).event("ford.conversation.inbound", Map.of(
                "channel", "WHATSAPP", "outcome", "success"));
    }

    @Test
    void recordsInboundMemoryBeforeTheTurnAndInvalidatesAfterTheReply() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 4));
        var memoryPort = new RecordingMemoryPort();
        var assistant = new RecordingAssistant(new AssistantReply("Respuesta", false));
        var publisher = new RecordingPublisher();

        var receiptId = UUID.randomUUID();
        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(true), new ConversationMemoryService(memoryPort, new MemoryProperties()),
                (id, messageId, decision) -> { }, new com.ford.service.observability.DefaultAiObservationService(
                io.micrometer.observation.ObservationRegistry.NOOP),
                (tenant, conversation, receipt) -> Optional.of(new AcceptedInboundMessage(
                        "wamid.memory", "customer-1", "¿Hay Ranger?", Instant.parse("2026-09-19T12:00:00Z"))))
                .on(new InboundMessageAccepted(receiptId, tenantId, conversationId, Channel.WHATSAPP,
                        "wamid.memory", Instant.parse("2026-09-19T12:00:00Z")));

        assertThat(memoryPort.inbound).isNotNull();
        assertThat(memoryPort.inbound.providerMessageId()).isEqualTo("wamid.memory");
        assertThat(memoryPort.inbound.content()).isEqualTo("¿Hay Ranger?");
        assertThat(publisher.events).anySatisfy(event ->
                assertThat(event).isInstanceOfSatisfying(ConversationMemoryProjectionRefreshRequested.class,
                        projection -> {
                            assertThat(projection.update().summary()).isEmpty();
                            assertThat(projection.expectedVersion()).isZero();
                        }));
        assertThat(memoryPort.invalidated).isEqualTo(conversationId);
    }

    @Test
    void loadsTheAcceptedReceiptTextInsteadOfAStaleConversationSnapshot() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 4));
        var assistant = new RecordingAssistant(new AssistantReply("Respuesta actual", false));

        new ConversationWorkflow(repository, assistant, new RecordingPublisher(),
                new HumanHandoffPolicy(false), memoryService(), (id, messageId, decision) -> { },
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP),
                (tenant, conversation, receipt) -> Optional.of(new AcceptedInboundMessage(
                        "wamid.latest", "customer-1", "¿Qué promociones hay?", Instant.now())))
                .on(new InboundMessageAccepted(UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                        "wamid.latest", Instant.now()));

        assertThat(assistant.requests).singleElement()
                .extracting(ConversationTurnRequest::message)
                .isEqualTo("¿Qué promociones hay?");
    }

    @Test
    void humanActiveConversationDoesNotInvokeAssistantOrPublishReply() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.HUMAN_ACTIVE, 7));
        var assistant = new RecordingAssistant(new AssistantReply(
                "No debe aparecer.", false));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(true), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                "wamid.2", Instant.now()));

        assertThat(assistant.requests).isEmpty();
        assertThat(repository.completedTurn).isNull();
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void directCustomerRequestPersistsHandoffAndPublishesOperatorNotification() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(new ConversationSnapshot(tenantId, conversationId,
                Channel.MESSENGER, "customer-1", ConversationState.BOT_ACTIVE, 2,
                "Quiero hablar con un especialista de Ford, por favor.", "msg.3"));
        var assistant = new RecordingAssistant(new AssistantReply(
                "Te comunico con un asesor.", true));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(true), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.MESSENGER,
                "msg.3", Instant.now()));

        assertThat(repository.handoffDecision.reason()).contains("Quiero hablar con un especialista de Ford");
        assertThat(repository.handoffExpectedVersion).isEqualTo(2);
        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(HandoffRequested.class, event -> {
            assertThat(event.tenantId()).isEqualTo(tenantId);
            assertThat(event.conversationId()).isEqualTo(conversationId);
            assertThat(event.reason()).contains("Quiero hablar con un especialista de Ford");
            assertThat(event.payload().arguments()).containsKeys("reason").doesNotContainKeys(
                    "vehicle", "needs", "unresolvedQuestion");
            assertThat(event.eventId()).isNotNull();
        });
    }

    @Test
    void modelHandoffRequestWithoutCustomerAuthorizationStaysInBotConversation() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 2));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository,
                new RecordingAssistant(new AssistantReply("Ya le comuniqué con un asesor.", true)), publisher,
                new HumanHandoffPolicy(true), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.MESSENGER, "msg.3-unapproved", Instant.now()));

        assertThat(repository.handoffDecision).isNull();
        assertThat(repository.completedTurn.text()).contains("seguir ayudándole por aquí")
                .doesNotContain("comuniqué con un asesor");
        assertThat(publisher.events).noneMatch(HandoffRequested.class::isInstance);
    }

    @Test
    void clearBuyingIntentOffersSpecialistHelpButDoesNotQueueSameTurn() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(new ConversationSnapshot(tenantId, conversationId,
                Channel.MESSENGER, "customer-1", ConversationState.BOT_ACTIVE, 2,
                "Estoy listo para comprar una Territory este mes. Busco espacio para mi familia.", "msg.buy"));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository,
                new RecordingAssistant(new AssistantReply("La Territory tiene estas opciones confirmadas.", false)),
                publisher, new HumanHandoffPolicy(true), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.MESSENGER, "msg.buy", Instant.now()));

        assertThat(repository.handoffDecision).isNull();
        assertThat(repository.completedTurn.text()).contains("opciones confirmadas")
                .contains("¿Desea que le conecte con un especialista Ford?");
        assertThat(publisher.events).noneMatch(HandoffRequested.class::isInstance);
    }

    @Test
    void disabledHandoffConvertsRequestedHandoffIntoOutboundBotReply() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 2));
        var assistant = new RecordingAssistant(new AssistantReply(
                "Te comunico con un asesor.", true));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(false), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.MESSENGER,
                "msg.3-disabled", Instant.now()));

        assertThat(repository.handoffDecision).isNull();
        assertThat(repository.completedTurn.text()).contains("seguir ayudándole por aquí")
                .doesNotContain("comunico con un asesor");
        assertThat(publisher.events).noneMatch(OutboundDeliveryRequested.class::isInstance);
    }

    @Test
    void staleConversationVersionDiscardsAssistantResult() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 3));
        repository.completeResult = false;
        var assistant = new RecordingAssistant(new AssistantReply(
                "Respuesta obsoleta.", false));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(true), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.INSTAGRAM,
                "msg.4", Instant.now()));

        assertThat(repository.completedTurn).isEqualTo(new ConversationTurn("Respuesta obsoleta."));
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void persistsClassifierEvidenceBeforeCompletingTheBotTurn() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 1));
        var decision = decision();
        var assistant = new RecordingAssistant(new AssistantReply("Respuesta", false, decision));
        var publisher = new RecordingPublisher();
        var decisions = new RecordingDecisionRepository();

        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(false), memoryService(), decisions).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                "wamid.decision", Instant.now()));

        assertThat(decisions.conversationId).isEqualTo(conversationId);
        assertThat(decisions.providerMessageId).isEqualTo("wamid.decision");
        assertThat(decisions.decision).isEqualTo(decision);
        assertThat(repository.completedTurn).isEqualTo(new ConversationTurn("Respuesta"));
    }

    private static ConversationDecision decision() {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE,
                List.of(new ClassificationEvidence(ClassifierType.INTENT, "GENERAL", 1.0,
                        "rules", "rules", "deterministic", 0, true)));
    }

    private static ConversationSnapshot snapshot(UUID tenantId, UUID conversationId,
                                                 ConversationState state, long version) {
        return new ConversationSnapshot(tenantId, conversationId, Channel.WHATSAPP,
                "5214521234567", state, version, "¿Hay Ranger?", "wamid.inbound");
    }

    private static ConversationMemoryService memoryService() {
        return new ConversationMemoryService(new ConversationMemoryPort() {
            @Override
            public ConversationMemoryContext load(MemoryQuery query) {
                return ConversationMemoryContext.empty(query.key());
            }

            @Override
            public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
            }

            @Override
            public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                        MemoryProjectionUpdate update,
                                                        long expectedVersion) {
                return new MemoryUpdateResult(false, expectedVersion);
            }
        }, new MemoryProperties());
    }

    private static final class RecordingAssistant implements ConversationAssistant {
        private final AssistantReply response;
        private final List<ConversationTurnRequest> requests = new ArrayList<>();

        private RecordingAssistant(AssistantReply response) {
            this.response = response;
        }

        @Override
        public AssistantReply respond(ConversationTurnRequest request) {
            requests.add(request);
            return response;
        }
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    private static final class RecordingMemoryPort implements ConversationMemoryPort {
        private ConversationMessage inbound;
        private UUID invalidated;

        @Override
        public ConversationMemoryContext load(MemoryQuery query) {
            return ConversationMemoryContext.empty(query.key());
        }

        @Override
        public void recordInbound(ConversationMemoryKey key, ConversationMessage message) {
            inbound = message;
        }

        @Override
        public MemoryUpdateResult updateProjection(ConversationMemoryKey key,
                                                    MemoryProjectionUpdate update,
                                                    long expectedVersion) {
            return new MemoryUpdateResult(true, expectedVersion + 1);
        }

        @Override
        public void invalidate(ConversationMemoryKey key) {
            invalidated = key.conversationId();
        }

    }

    private static final class RecordingConversationRepository implements ConversationRepository {
        private final ConversationSnapshot snapshot;
        private ConversationTurn completedTurn;
        private long completedExpectedVersion;
        private HandoffDecision handoffDecision;
        private long handoffExpectedVersion;
        private boolean completeResult = true;

        private RecordingConversationRepository(ConversationSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<ConversationSnapshot> find(UUID conversationId) {
            return Optional.of(snapshot);
        }

        @Override
        public boolean completeBotTurn(UUID conversationId, long expectedVersion, ConversationTurn turn) {
            completedExpectedVersion = expectedVersion;
            completedTurn = turn;
            return completeResult;
        }

        @Override
        public boolean requestHumanHandoff(UUID conversationId, long expectedVersion, HandoffDecision decision) {
            handoffExpectedVersion = expectedVersion;
            handoffDecision = decision;
            return true;
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
        }
    }

    private static final class RecordingDecisionRepository implements ConversationDecisionRepository {
        private UUID conversationId;
        private String providerMessageId;
        private ConversationDecision decision;

        @Override
        public void save(UUID conversationId, String providerMessageId, ConversationDecision decision) {
            this.conversationId = conversationId;
            this.providerMessageId = providerMessageId;
            this.decision = decision;
        }
    }
}
