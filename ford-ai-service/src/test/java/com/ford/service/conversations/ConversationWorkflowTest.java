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
import com.ford.service.conversations.application.ConversationDecisionRepository;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.domain.ConversationSnapshot;
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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationWorkflowTest {

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
        assertThat(publisher.events).singleElement().isInstanceOf(OutboundDeliveryRequested.class);
    }

    @Test
    void recordsInboundMemoryBeforeTheTurnAndInvalidatesAfterTheReply() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 4));
        var memoryPort = new RecordingMemoryPort();
        var assistant = new RecordingAssistant(new AssistantReply("Respuesta", false));

        new ConversationWorkflow(repository, assistant, new RecordingPublisher(),
                new HumanHandoffPolicy(true), new ConversationMemoryService(memoryPort, new MemoryProperties()))
                .on(new InboundMessageAccepted(
                        UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                        "wamid.memory", Instant.parse("2026-09-19T12:00:00Z"),
                        "customer-1", "¿Hay Ranger?"));

        assertThat(memoryPort.inbound).isNotNull();
        assertThat(memoryPort.inbound.providerMessageId()).isEqualTo("wamid.memory");
        assertThat(memoryPort.inbound.content()).isEqualTo("¿Hay Ranger?");
        assertThat(memoryPort.projection).isNotNull();
        assertThat(memoryPort.projection.summary()).contains("¿Hay Ranger?");
        assertThat(memoryPort.projectionExpectedVersion).isZero();
        assertThat(memoryPort.invalidated).isEqualTo(conversationId);
    }

    @Test
    void usesTheAcceptedEventTextInsteadOfAStaleConversationSnapshot() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 4));
        var assistant = new RecordingAssistant(new AssistantReply("Respuesta actual", false));

        new ConversationWorkflow(repository, assistant, new RecordingPublisher(),
                new HumanHandoffPolicy(false), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.WHATSAPP,
                "wamid.latest", Instant.now(), "customer-1", "¿Qué promociones hay?"));

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
    void handoffResponsePersistsDecisionAndPublishesOperatorNotification() {
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var repository = new RecordingConversationRepository(snapshot(
                tenantId, conversationId, ConversationState.BOT_ACTIVE, 2));
        var assistant = new RecordingAssistant(new AssistantReply(
                "Te comunico con un asesor.", true));
        var publisher = new RecordingPublisher();

        new ConversationWorkflow(repository, assistant, publisher,
                new HumanHandoffPolicy(true), memoryService()).on(new InboundMessageAccepted(
                UUID.randomUUID(), tenantId, conversationId, Channel.MESSENGER,
                "msg.3", Instant.now()));

        assertThat(repository.handoffDecision).isEqualTo(new HandoffDecision("assistant_requested_handoff"));
        assertThat(repository.handoffExpectedVersion).isEqualTo(2);
        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(HandoffRequested.class, event -> {
            assertThat(event.tenantId()).isEqualTo(tenantId);
            assertThat(event.conversationId()).isEqualTo(conversationId);
            assertThat(event.reason()).isEqualTo("assistant_requested_handoff");
            assertThat(event.eventId()).isNotNull();
        });
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
        assertThat(repository.completedTurn.text()).contains("No pude generar");
        assertThat(publisher.events).singleElement().isInstanceOf(OutboundDeliveryRequested.class);
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
        private MemoryProjectionUpdate projection;
        private long projectionExpectedVersion = -1;
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
            projection = update;
            projectionExpectedVersion = expectedVersion;
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
