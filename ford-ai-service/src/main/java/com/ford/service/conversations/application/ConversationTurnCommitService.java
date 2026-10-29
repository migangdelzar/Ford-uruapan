package com.ford.service.conversations.application;

import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
import com.ford.service.conversations.application.ConversationTurnState;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.ai.application.DecisionTraceCollector;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConversationTurnCommitService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnCommitService.class);
    private static final String UNAUTHORIZED_HANDOFF_TEXT =
            "Puedo seguir ayudándole por aquí. ¿Qué información desea revisar?";
    private static final String HANDOFF_UNAVAILABLE_TEXT =
            "No se pudo completar el traspaso a un especialista. Puedo seguir ayudándole por aquí.";
    private static final String HANDOFF_QUEUE_CONFIRMATION =
            "Su solicitud quedó registrada en la fila del equipo de Ford Service. "
                    + "Aún no tengo confirmación de cuándo podrá atenderle.";

    private final ConversationRepository repository;
    private final ConversationDecisionRepository decisionRepository;
    private final LeadJourneyService leadJourneyService;
    private final HumanHandoffPolicy handoffPolicy;
    private final ConversationMemoryService memoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConversationTurnLedgerPort turnLedger;
    private final DecisionTraceRepository decisionTraceRepository;
    private final AlternateContactStagingPort alternateContactStaging;
    private final CatalogNameIndexService catalogNameIndex;
    private final CustomerChoicePresentationService choicePresenter;
    private final CustomerHandoffAuthorizationPolicy customerAuthorization =
            new CustomerHandoffAuthorizationPolicy();
    private final CustomerHandoffEscalationPolicy handoffEscalationPolicy =
            new CustomerHandoffEscalationPolicy();
    private final SpecialistHandoffPayloadFactory payloadFactory = new SpecialistHandoffPayloadFactory();
    private final CustomerHandoffOfferPolicy handoffOfferPolicy = new CustomerHandoffOfferPolicy();

    public int consecutiveSubstantiveFailures(UUID tenantId, UUID conversationId) {
        return turnLedger.consecutiveSubstantiveFailures(tenantId, conversationId);
    }

    public boolean humanHandoffEnabled(UUID tenantId) {
        return handoffPolicy.enabled(tenantId);
    }

    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                          ConversationMemoryService memoryService,
                                          ApplicationEventPublisher eventPublisher,
                                         ConversationTurnLedgerPort turnLedger,
                                         DecisionTraceRepository decisionTraceRepository,
                                         AlternateContactStagingPort alternateContactStaging) {
        this(repository, decisionRepository, leadJourneyService, handoffPolicy, memoryService, eventPublisher,
                turnLedger, decisionTraceRepository, alternateContactStaging, null,
                new CustomerChoicePresentationService());
    }

    @Autowired
    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                         ConversationMemoryService memoryService,
                                         ApplicationEventPublisher eventPublisher,
                                         ConversationTurnLedgerPort turnLedger,
                                         DecisionTraceRepository decisionTraceRepository,
                                         AlternateContactStagingPort alternateContactStaging,
                                         @org.springframework.lang.Nullable
                                         CatalogNameIndexService catalogNameIndex,
                                         CustomerChoicePresentationService choicePresenter) {
        this.repository = repository;
        this.decisionRepository = decisionRepository;
        this.leadJourneyService = leadJourneyService;
        this.handoffPolicy = handoffPolicy;
        this.memoryService = memoryService;
        this.eventPublisher = eventPublisher;
        this.turnLedger = turnLedger;
        this.decisionTraceRepository = decisionTraceRepository;
        this.alternateContactStaging = alternateContactStaging;
        this.catalogNameIndex = catalogNameIndex;
        this.choicePresenter = Objects.requireNonNull(choicePresenter);
    }

    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                         ConversationMemoryService memoryService,
                                         ApplicationEventPublisher eventPublisher,
                                         ConversationTurnLedgerPort turnLedger,
                                         DecisionTraceRepository decisionTraceRepository) {
        this(repository, decisionRepository, leadJourneyService, handoffPolicy, memoryService, eventPublisher,
                turnLedger, decisionTraceRepository, null);
    }

    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                         ConversationMemoryService memoryService,
                                         ApplicationEventPublisher eventPublisher,
                                         ConversationTurnLedgerPort turnLedger) {
        this(repository, decisionRepository, leadJourneyService, handoffPolicy, memoryService, eventPublisher,
                turnLedger, emptyTraceRepository());
    }

    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                         ConversationMemoryService memoryService,
                                         ApplicationEventPublisher eventPublisher) {
        this(repository, decisionRepository, leadJourneyService, handoffPolicy, memoryService, eventPublisher,
                new ConversationTurnLedgerPort() {
                    @Override
                    public void recordOutcome(UUID tenantId, UUID conversationId, UUID receiptId,
                                              String providerMessageId, ConversationTurnState state,
                                              String failureCategory, Instant occurredAt) { }

                    @Override
                    public void recordReview(UUID tenantId, UUID conversationId, UUID receiptId,
                                             String reasonCode, Instant createdAt) { }
                }, emptyTraceRepository());
    }

    private static DecisionTraceRepository emptyTraceRepository() {
        return new DecisionTraceRepository() {
            @Override public void append(UUID tenantId, UUID conversationId, UUID receiptId,
                                         java.util.List<DecisionTraceEvent> events) { }
            @Override public java.util.List<DecisionTraceEvent> list(UUID tenantId, UUID conversationId,
                                                                     UUID receiptId) { return java.util.List.of(); }
        };
    }

    @Transactional
    public ConversationGraphRoute commit(ConversationTurnReference reference, AcceptedInboundMessage inbound,
                                          ConversationSnapshot conversation, ConversationMemoryContext memory,
                                          LeadJourneySnapshot journey, com.ford.service.conversations.api.AssistantReply reply) {
        return commit(reference, inbound, conversation, memory, journey, reply, null, java.util.List.of());
    }

    @Transactional
    public ConversationGraphRoute commit(ConversationTurnReference reference, AcceptedInboundMessage inbound,
                                          ConversationSnapshot conversation, ConversationMemoryContext memory,
                                          LeadJourneySnapshot journey, com.ford.service.conversations.api.AssistantReply reply,
                                          java.util.List<AlternateContactValue> alternateContacts) {
        return commit(reference, inbound, conversation, memory, journey, reply, null,
                alternateContacts == null ? java.util.List.of() : java.util.List.copyOf(alternateContacts));
    }

    @Transactional
    public ConversationGraphRoute commitSafeFallback(ConversationTurnReference reference,
                                                      AcceptedInboundMessage inbound,
                                                      ConversationSnapshot conversation,
                                                      ConversationMemoryContext memory,
                                                      LeadJourneySnapshot journey,
                                                      String fallbackText,
                                                      String reasonCode) {
        return commit(reference, inbound, conversation, memory, journey,
                new com.ford.service.conversations.api.AssistantReply(fallbackText, false), reasonCode,
                java.util.List.of());
    }

    private ConversationGraphRoute commit(ConversationTurnReference reference, AcceptedInboundMessage inbound,
                                          ConversationSnapshot conversation, ConversationMemoryContext memory,
                                           LeadJourneySnapshot journey,
                                           com.ford.service.conversations.api.AssistantReply reply,
                                           String reviewReasonCode,
                                           java.util.List<AlternateContactValue> alternateContacts) {
        var providerMessageId = inbound.providerMessageId();
        log.info("Conversation commit started: conversationId={} providerMessageId={} handoffRequired={} decisionPresent={} journeyProposalPresent={}",
                reference.conversationId(), providerMessageId, reply.handoffRequired(), reply.decision() != null,
                reply.journeyProposal() != null);
        var customerAuthorized = customerAuthorization.isAuthorized(inbound.text(), memory);
        var escalated = handoffEscalationPolicy.shouldEscalate(inbound.text(), memory);
        var createHandoff = handoffPolicy.shouldCreateHandoff(reference.tenantId(), customerAuthorized || escalated);
        if (!createHandoff && reply.decision() != null) {
            decisionRepository.save(conversation.conversationId(), providerMessageId, reply.decision());
        }
        if (reply.journeyProposal() != null) {
            var sensitive = reply.decision() != null
                    && reply.decision().sensitiveDataLevel() != SensitiveDataLevel.NONE;
            leadJourneyService.apply(reference.tenantId(), reference.conversationId(), journey,
                    reply.journeyProposal(), inbound.text(), sensitive, providerMessageId);
        }

        if (createHandoff) {
            var payload = payloadFactory.create(inbound.text(), memory, reply.text());
            var decision = reply.decision();
            var urgent = escalated
                    || decision.intent() == ConversationIntent.HUMAN_REQUEST
                    || decision.handoffRecommendation() == HandoffRecommendation.USER_REQUESTED
                    || decision.leadTemperature() == LeadTemperature.HOT;
            var reason = payload.reason();
            var internalNote = payloadFactory.createInternalNote(journey, reply.decisionTrace(), reply.decision(),
                    urgent ? "HIGH" : "NORMAL");
            var packet = payloadFactory.createPacket(journey, inbound.text(), memory);
            if (alternateContacts != null && !alternateContacts.isEmpty()) {
                packet = packet.withAlternateContact(alternateContacts.getFirst());
            }
            var handoff = new HandoffDecision(reason,
                            urgent ? "HIGH" : "NORMAL",
                            handoffIdempotencyKey(reference, inbound.providerMessageId()),
                            packet, internalNote);
            return commitHandoff(reference, inbound, conversation, handoff, payload, reply.decision(),
                    traceEvents(reply), HANDOFF_QUEUE_CONFIRMATION);
        }

        var handoffEnabled = handoffPolicy.enabled(reference.tenantId());
        var shouldOffer = handoffEnabled
                && handoffOfferPolicy.shouldOffer(inbound.text(), reply.text(),
                        reply.decision() == null ? LeadTemperature.UNKNOWN : reply.decision().leadTemperature(), memory);
        var text = customerAuthorized && !handoffEnabled ? HANDOFF_UNAVAILABLE_TEXT
                : reply.handoffRequired() ? shouldOffer ? handoffOfferPolicy.offerAfter(null) : UNAUTHORIZED_HANDOFF_TEXT
                : shouldOffer ? handoffOfferPolicy.offerAfter(reply.text()) : reply.text();
        var currentNames = catalogNameIndex == null ? java.util.List.<com.ford.service.catalog.api.VehicleNameIndexEntry>of()
                : catalogNameIndex.currentEntries();
        var replyOptions = choicePresenter.fromAnswer(text, currentNames);
        if (!repository.completeBotTurn(reference.tenantId(), reference.conversationId(), conversation.version(),
                providerMessageId, new ConversationTurn(text, replyOptions))) {
            log.warn("Conversation bot commit rejected: conversationId={} providerMessageId={} reason=optimistic_lock_or_duplicate",
                    reference.conversationId(), providerMessageId);
            return ConversationGraphRoute.DUPLICATE;
        }
        var committedAt = Instant.now();
        decisionRepository.save(conversation.conversationId(), inbound.providerMessageId(),
                com.ford.service.ai.application.ConversationDecision.deterministicDefault());
        turnLedger.recordOutcome(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                providerMessageId, reviewReasonCode == null ? ConversationTurnState.REPLY_COMMITTED
                        : ConversationTurnState.SAFE_FALLBACK_REVIEW,
                reviewReasonCode, committedAt);
        if (reviewReasonCode != null) {
            turnLedger.recordReview(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                    reviewReasonCode, committedAt);
        }
        var trace = new DecisionTraceCollector();
        traceEvents(reply).forEach(trace::record);
        if (reviewReasonCode != null) {
            trace.record(new DecisionTraceEvent("quality", "safe_fallback_review", "SAFE_FALLBACK_REVIEW",
                    "none", null, "fallback", null, committedAt));
        }
        decisionTraceRepository.append(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                trace.snapshot());
        var key = memory.key();
        memoryService.indexOutbound(key, outboundMessageId(providerMessageId),
                "outbound:" + providerMessageId, text, Instant.now());
        eventPublisher.publishEvent(new ConversationMemoryProjectionRefreshRequested(
                key, memoryService.project(memory, inbound.text(), reference.receiptId(), text), memory.memoryVersion()));
        memoryService.invalidate(key);
        var temperature = reply.decision() == null || reply.decision().leadTemperature() == null
                ? "UNKNOWN" : reply.decision().leadTemperature().name();
        eventPublisher.publishEvent(new com.ford.service.shared.ConversationTurnCompleted(
                reference.tenantId(), reference.conversationId(), providerMessageId,
                temperature, inbound.text(), reply.text()));
        log.info("Conversation bot reply committed: conversationId={} providerMessageId={} route=BOT_REPLY durableOutbox=true leadTemperature={}",
                reference.conversationId(), providerMessageId, temperature);
        return reviewReasonCode == null ? ConversationGraphRoute.BOT_REPLY
                : ConversationGraphRoute.SAFE_FALLBACK_REVIEW;
    }

    @Transactional
    public ConversationGraphRoute forceHumanHandoff(ConversationTurnReference reference,
                                                     AcceptedInboundMessage inbound,
                                                     ConversationSnapshot conversation,
                                                     ConversationMemoryContext memory,
                                                     LeadJourneySnapshot journey) {
        return forceHumanHandoff(reference, inbound, conversation, memory, journey, java.util.List.of(),
                null, java.util.List.of());
    }

    @Transactional
    public ConversationGraphRoute forceHumanHandoff(ConversationTurnReference reference,
                                                     AcceptedInboundMessage inbound,
                                                     ConversationSnapshot conversation,
                                                     ConversationMemoryContext memory,
                                                     LeadJourneySnapshot journey,
                                                     java.util.List<AlternateContactValue> alternateContacts) {
        return forceHumanHandoff(reference, inbound, conversation, memory, journey, alternateContacts,
                null, java.util.List.of());
    }

    @Transactional
    public ConversationGraphRoute forceHumanHandoff(ConversationTurnReference reference,
                                                     AcceptedInboundMessage inbound,
                                                     ConversationSnapshot conversation,
                                                     ConversationMemoryContext memory,
                                                     LeadJourneySnapshot journey,
                                                     java.util.List<AlternateContactValue> alternateContacts,
                                                     com.ford.service.ai.application.ConversationDecision decision,
                                                     java.util.List<DecisionTraceEvent> decisionTrace) {
        var payload = payloadFactory.create(inbound.text(), memory, null);
        var handoffDecision = decision == null
                ? com.ford.service.ai.application.ConversationDecision.deterministicDefault() : decision;
        var handoffTrace = decisionTrace == null ? java.util.List.<DecisionTraceEvent>of() : decisionTrace;
        var internalNote = payloadFactory.createInternalNote(journey, handoffTrace,
                handoffDecision, "HIGH");
        var packet = payloadFactory.createPacket(journey, inbound.text(), memory);
        if (alternateContacts != null && !alternateContacts.isEmpty()) {
            packet = packet.withAlternateContact(alternateContacts.getFirst());
        }
        var handoff = new HandoffDecision(payload.reason(), "HIGH",
                handoffIdempotencyKey(reference, inbound.providerMessageId()), packet, internalNote);
        return commitHandoff(reference, inbound, conversation, handoff, payload, decision,
                handoffTrace,
                "La atención automatizada está pausada. Su conversación quedó en la fila del equipo de Ford Service.");
    }

    private ConversationGraphRoute commitHandoff(ConversationTurnReference reference,
                                                   AcceptedInboundMessage inbound,
                                                   ConversationSnapshot conversation,
                                                   HandoffDecision handoff,
                                                   com.ford.service.conversations.api.SpecialistHandoffPayload payload,
                                                   com.ford.service.ai.application.ConversationDecision decision,
                                                   java.util.List<DecisionTraceEvent> traceEvents,
                                                   String confirmation) {
        if (!handoff.reason().equals(payload.reason())) {
            throw new IllegalArgumentException("handoff reason must match its customer-safe payload");
        }
        var accepted = repository.requestHumanHandoff(reference.tenantId(), reference.conversationId(),
                conversation.version(), inbound.providerMessageId(), handoff, new ConversationTurn(confirmation));
        if (!accepted) {
            log.warn("Conversation handoff commit rejected: conversationId={} providerMessageId={} reason=optimistic_lock_or_duplicate",
                    reference.conversationId(), inbound.providerMessageId());
            return ConversationGraphRoute.DUPLICATE;
        }
        var committedAt = Instant.now();
        if (decision != null) {
            decisionRepository.save(conversation.conversationId(), inbound.providerMessageId(), decision);
        }
        turnLedger.recordOutcome(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                inbound.providerMessageId(), ConversationTurnState.HUMAN_PENDING, null, committedAt);
        var handoffTrace = new DecisionTraceCollector();
        if (traceEvents != null) traceEvents.forEach(handoffTrace::record);
        handoffTrace.record(new DecisionTraceEvent("handoff", "handoff_requested", "HUMAN_PENDING",
                "none", null, "queued", null, committedAt));
        decisionTraceRepository.append(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                handoffTrace.snapshot());
        eventPublisher.publishEvent(new HandoffRequested(UUID.randomUUID(), reference.tenantId(),
                reference.conversationId(), handoff.reason(), committedAt, payload, handoff.internalNote()));
        deleteAlternateContactStaging(reference);
        log.info("Conversation handoff committed: conversationId={} providerMessageId={} reason={}",
                reference.conversationId(), inbound.providerMessageId(), handoff.reason());
        return ConversationGraphRoute.HUMAN_PENDING;
    }

    private void deleteAlternateContactStaging(ConversationTurnReference reference) {
        if (alternateContactStaging != null) {
            alternateContactStaging.delete(reference.tenantId(), reference.conversationId(), reference.receiptId());
        }
    }

    private static String handoffIdempotencyKey(ConversationTurnReference reference, String providerMessageId) {
        try {
            var input = reference.tenantId() + ":" + reference.conversationId() + ":" + providerMessageId;
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "inbound:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID outboundMessageId(String providerMessageId) {
        return UUID.nameUUIDFromBytes(("outbound:" + providerMessageId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static java.util.List<DecisionTraceEvent> traceEvents(
            com.ford.service.conversations.api.AssistantReply reply) {
        if (!reply.decisionTrace().isEmpty()) return reply.decisionTrace();
        var decision = reply.decision();
        return java.util.List.of(new DecisionTraceEvent("route", "decision_route_committed",
                decision.intent().name(), "none", null, "completed", decision.evidence().size(), Instant.now()));
    }
}
