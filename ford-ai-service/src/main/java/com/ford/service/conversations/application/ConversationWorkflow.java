package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.api.InboundDebounceGate;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ConversationTurnLeasePort;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;
import com.ford.service.conversations.domain.MemoryUpdateResult;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

public class ConversationWorkflow {
    private final com.ford.service.shared.AdvisorResponseRepeatPolicy repeatPolicy =
            new com.ford.service.shared.AdvisorResponseRepeatPolicy();

    private static final Logger log = LoggerFactory.getLogger(ConversationWorkflow.class);
    private static final Duration TURN_LEASE_DURATION = Duration.ofMinutes(15);
    private static final String UNAUTHORIZED_HANDOFF_TEXT =
            "Puedo seguir ayudándole por aquí. ¿Qué información desea revisar?";
    private static final String HANDOFF_UNAVAILABLE_TEXT =
            "No se pudo completar el traspaso a un especialista. Puedo seguir ayudándole por aquí.";

    private final ConversationRepository repository;
    private final ConversationAssistant assistant;
    private final ApplicationEventPublisher eventPublisher;
    private final HumanHandoffPolicy handoffPolicy;
    private final ConversationMemoryService memoryService;
    private final ConversationDecisionRepository decisionRepository;
    private final AiObservationService observations;
    private final ConversationGraphRunner graphRunner;
    private final AcceptedInboundMessageRepository inboundTurns;
    private final ConversationTurnLeasePort turnLeases;
    private final InboundDebounceGate debounceGate;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final CustomerHandoffAuthorizationPolicy customerAuthorization =
            new CustomerHandoffAuthorizationPolicy();
    private final CustomerHandoffEscalationPolicy handoffEscalationPolicy =
            new CustomerHandoffEscalationPolicy();
    private final SpecialistHandoffPayloadFactory handoffPayloadFactory = new SpecialistHandoffPayloadFactory();
    private final CustomerHandoffOfferPolicy handoffOfferPolicy = new CustomerHandoffOfferPolicy();

    /**
     * Compatibility constructor for lightweight callers that do not provide
     * durable memory or classifier evidence adapters.
     */
    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher) {
        this(repository, assistant, eventPublisher, new HumanHandoffPolicy(true), noOpMemoryService());
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService) {
        this(repository, assistant, eventPublisher, handoffPolicy, memoryService,
                (conversationId, providerMessageId, decision) -> { },
                new DefaultAiObservationService(ObservationRegistry.NOOP), null);
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService,
                                ConversationDecisionRepository decisionRepository) {
        this(repository, assistant, eventPublisher, handoffPolicy, memoryService, decisionRepository,
                new DefaultAiObservationService(ObservationRegistry.NOOP), null);
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService,
                                ConversationDecisionRepository decisionRepository,
                                AiObservationService observations) {
        this(repository, assistant, eventPublisher, handoffPolicy, memoryService, decisionRepository,
                observations, null);
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService,
                                ConversationDecisionRepository decisionRepository,
                                AiObservationService observations,
                                AcceptedInboundMessageRepository inboundTurns) {
        this(repository, assistant, eventPublisher, handoffPolicy, memoryService, decisionRepository,
                observations, inboundTurns, null);
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService,
                                ConversationDecisionRepository decisionRepository,
                                AiObservationService observations,
                                AcceptedInboundMessageRepository inboundTurns,
                                ConversationTurnLeasePort turnLeases) {
        this(repository, assistant, eventPublisher, handoffPolicy, memoryService, decisionRepository,
                observations, inboundTurns, turnLeases, null);
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService,
                                ConversationDecisionRepository decisionRepository,
                                AiObservationService observations,
                                AcceptedInboundMessageRepository inboundTurns,
                                ConversationTurnLeasePort turnLeases,
                                InboundDebounceGate debounceGate) {
        this.repository = Objects.requireNonNull(repository);
        this.assistant = Objects.requireNonNull(assistant);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
        this.memoryService = Objects.requireNonNull(memoryService);
        this.decisionRepository = Objects.requireNonNull(decisionRepository);
        this.observations = Objects.requireNonNull(observations);
        this.graphRunner = null;
        this.inboundTurns = inboundTurns;
        this.turnLeases = turnLeases;
        this.debounceGate = debounceGate;
    }

    public ConversationWorkflow(ConversationGraphRunner graphRunner, AiObservationService observations) {
        this(graphRunner, observations, null);
    }

    public ConversationWorkflow(ConversationGraphRunner graphRunner, AiObservationService observations,
                                ConversationTurnLeasePort turnLeases) {
        this(graphRunner, observations, turnLeases, null);
    }

    public ConversationWorkflow(ConversationGraphRunner graphRunner, AiObservationService observations,
                                ConversationTurnLeasePort turnLeases, InboundDebounceGate debounceGate) {
        this.repository = null;
        this.assistant = null;
        this.eventPublisher = null;
        this.handoffPolicy = null;
        this.memoryService = null;
        this.decisionRepository = null;
        this.observations = Objects.requireNonNull(observations);
        this.graphRunner = Objects.requireNonNull(graphRunner);
        this.inboundTurns = null;
        this.turnLeases = turnLeases;
        this.debounceGate = debounceGate;
    }

    private static ConversationMemoryService noOpMemoryService() {
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

    @ApplicationModuleListener
    @Async("conversationTurnExecutor")
    public void on(InboundMessageAccepted event) {
        dispatch(event, true);
    }

    @ApplicationModuleListener
    @Async("conversationTurnExecutor")
    public void on(InboundTurnReleased event) {
        dispatch(event.asInboundMessageAccepted(), false);
    }

    private void dispatch(InboundMessageAccepted event, boolean honorDebounce) {
        var started = System.nanoTime();
        var previousConversationId = MDC.get("conversationId");
        var previousReceiptId = MDC.get("receiptId");
        var previousProviderMessageId = MDC.get("providerMessageId");
        var previousStage = MDC.get("workflowStage");
        MDC.put("conversationId", event.conversationId().toString());
        MDC.put("receiptId", event.receiptId().toString());
        MDC.put("providerMessageId", event.providerMessageId());
        MDC.put("workflowStage", "RECEIVED");
        log.info("Conversation workflow received inbound event: conversationId={} receiptId={} providerMessageId={} channel={}",
                event.conversationId(), event.receiptId(), event.providerMessageId(), event.channel());
        var leased = turnLeases == null;
        try {
            if (turnLeases != null) {
                leased = turnLeases.tryAcquire(event.tenantId(), event.conversationId(), leaseOwner,
                        TURN_LEASE_DURATION);
                if (!leased) {
                    log.warn("Conversation workflow could not acquire turn lease: conversationId={} receiptId={} action=retain_event_for_retry",
                            event.conversationId(), event.receiptId());
                    throw new ConversationTurnLeaseUnavailableException(event.conversationId());
                }
            }
            if (honorDebounce && debounceGate != null
                    && !debounceGate.isDue(event.tenantId(), event.conversationId(), event.receiptId())) {
                MDC.put("workflowStage", "DEBOUNCE_DEFERRED");
                log.info("Conversation workflow deferred for debounce: conversationId={} receiptId={}",
                        event.conversationId(), event.receiptId());
                return;
            }
            MDC.put("workflowStage", "WORKFLOW_STARTED");
            log.info("Conversation workflow executing: conversationId={} mode={}", event.conversationId(),
                    graphRunner == null ? "legacy" : "graph");
            if (graphRunner == null) {
                MDC.put("workflowStage", "LEGACY_PROCESSING");
                handle(event);
            } else {
                MDC.put("workflowStage", "GRAPH_PROCESSING");
                var result = graphRunner.run(new ConversationTurnReference(
                        event.tenantId(), event.conversationId(), event.receiptId()));
                MDC.put("workflowStage", result.route().name());
                log.info("Conversation workflow completed: conversationId={} route={} terminal={} durationMs={}",
                        event.conversationId(), result.route(), result.terminal(), elapsedMs(started));
            }
            observations.event("ford.conversation.inbound", java.util.Map.of(
                    "channel", event.channel().name(), "outcome", "success"));
        } catch (RuntimeException exception) {
            log.error("Conversation workflow failed: conversationId={} receiptId={} exceptionType={} durationMs={}",
                    event.conversationId(), event.receiptId(), exception.getClass().getSimpleName(),
                    elapsedMs(started), exception);
            observations.event("ford.conversation.inbound", java.util.Map.of(
                    "channel", event.channel().name(), "outcome", "failure"));
            throw exception;
        } finally {
            if (turnLeases != null && leased) {
                turnLeases.release(event.tenantId(), event.conversationId(), leaseOwner);
            }
            restoreMdc("conversationId", previousConversationId);
            restoreMdc("receiptId", previousReceiptId);
            restoreMdc("providerMessageId", previousProviderMessageId);
            restoreMdc("workflowStage", previousStage);
        }
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private void handle(InboundMessageAccepted event) {
        var snapshot = repository.find(event.conversationId()).orElse(null);
        if (snapshot == null || !canUseAssistant(snapshot.state())) {
            return;
        }

        // Load canonical text by receipt; never rely on the conversation's latest
        // message because a later webhook may already have updated that snapshot.
        var acceptedTurn = inboundTurns == null ? null
                : inboundTurns.find(event.tenantId(), event.conversationId(), event.receiptId()).orElse(null);
        if (inboundTurns != null && acceptedTurn == null) return;
        var currentMessage = acceptedTurn == null ? snapshot.inboundText() : acceptedTurn.text();
        var participantId = acceptedTurn == null ? snapshot.recipientId() : acceptedTurn.participantId();

        var memoryKey = new ConversationMemoryKey(snapshot.tenantId(), snapshot.channel(),
                snapshot.providerAccountId(), participantId, snapshot.conversationId());
        memoryService.recordInbound(memoryKey, event.receiptId(), event.providerMessageId(), currentMessage,
                event.acceptedAt());
        var memory = memoryService.load(new MemoryQuery(memoryKey, currentMessage,
                snapshot.providerMessageId()));
        var reply = assistant.respond(new ConversationTurnRequest(
                snapshot.tenantId(), snapshot.conversationId(), currentMessage, snapshot.state(), memory));
        try {
            decisionRepository.save(snapshot.conversationId(), event.providerMessageId(), reply.decision());
        } catch (RuntimeException exception) {
            log.warn("Conversation decision persistence failed: conversationId={} providerMessageId={} exceptionType={}",
                    snapshot.conversationId(), event.providerMessageId(), exception.getClass().getSimpleName());
        }

        var customerAuthorized = customerAuthorization.isAuthorized(currentMessage, memory);
        var requested = customerAuthorized || handoffEscalationPolicy.shouldEscalate(currentMessage, memory);
        if (requested && handoffPolicy.shouldCreateHandoff(snapshot.tenantId(), true)) {
            var payload = handoffPayloadFactory.create(currentMessage, memory, reply.text());
            var accepted = repository.requestHumanHandoff(snapshot.conversationId(), snapshot.version(),
                    new HandoffDecision(payload.reason()));
            if (accepted) {
                eventPublisher.publishEvent(new HandoffRequested(
                        UUID.randomUUID(), snapshot.tenantId(), snapshot.conversationId(),
                        payload.reason(), Instant.now(), payload));
            }
            return;
        }

        var handoffEnabled = handoffPolicy.enabled(snapshot.tenantId());
        var shouldOffer = handoffEnabled
                && handoffOfferPolicy.shouldOffer(currentMessage, reply.text(),
                        reply.decision() == null ? com.ford.service.ai.application.LeadTemperature.UNKNOWN
                                : reply.decision().leadTemperature(), memory);
        var text = requested && !handoffEnabled ? HANDOFF_UNAVAILABLE_TEXT
                : reply.handoffRequired() ? shouldOffer ? handoffOfferPolicy.offerAfter(null) : UNAUTHORIZED_HANDOFF_TEXT
                : shouldOffer ? handoffOfferPolicy.offerAfter(reply.text()) : reply.text();
        var priorReplies = memory.recentMessages().stream()
                .filter(message -> message.direction() == ConversationMessage.Direction.OUTBOUND)
                .map(ConversationMessage::content).toList();
        var priorQuestions = memory.recentMessages().stream()
                .filter(message -> message.direction() == ConversationMessage.Direction.INBOUND)
                .map(ConversationMessage::content).toList();
        var repeat = repeatPolicy.evaluate(currentMessage, priorQuestions, priorReplies, text);
        if (repeat.repeated()) {
            log.warn("Legacy conversation reply held by final repeat guard: conversationId={} reason={}",
                    snapshot.conversationId(), repeat.reasonCode());
            throw new com.ford.service.ai.api.AssistantTurnUnavailableException(
                    com.ford.service.ai.api.AssistantTurnUnavailableException.Disposition.REVIEW);
        }
        var accepted = repository.completeBotTurn(snapshot.conversationId(), snapshot.version(),
                new ConversationTurn(text));
        if (accepted) {
            memoryService.indexOutbound(memoryKey,
                    UUID.nameUUIDFromBytes(("outbound:" + event.providerMessageId())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "outbound:" + event.providerMessageId(), text, Instant.now());
            eventPublisher.publishEvent(new ConversationMemoryProjectionRefreshRequested(
                    memoryKey,
                    memoryService.project(memory, currentMessage, event.receiptId(), text),
                    memory.memoryVersion()));
            memoryService.invalidate(memoryKey);
        }
    }

    private static boolean canUseAssistant(ConversationState state) {
        return state == ConversationState.BOT_ACTIVE || state == ConversationState.LEAD_CAPTURE;
    }
}
