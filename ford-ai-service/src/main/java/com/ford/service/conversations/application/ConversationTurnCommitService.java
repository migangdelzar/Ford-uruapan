package com.ford.service.conversations.application;

import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationSnapshot;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadTemperature;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "true")
public class ConversationTurnCommitService {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnCommitService.class);
    private static final String UNAUTHORIZED_HANDOFF_TEXT =
            "Puedo seguir ayudándole por aquí. ¿Qué información desea revisar?";
    private static final String HANDOFF_UNAVAILABLE_TEXT =
            "No se pudo completar el traspaso a un especialista. Puedo seguir ayudándole por aquí.";

    private final ConversationRepository repository;
    private final ConversationDecisionRepository decisionRepository;
    private final LeadJourneyService leadJourneyService;
    private final HumanHandoffPolicy handoffPolicy;
    private final ConversationMemoryService memoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerHandoffAuthorizationPolicy customerAuthorization =
            new CustomerHandoffAuthorizationPolicy();
    private final SpecialistHandoffPayloadFactory payloadFactory = new SpecialistHandoffPayloadFactory();
    private final CustomerHandoffOfferPolicy handoffOfferPolicy = new CustomerHandoffOfferPolicy();

    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                         ConversationMemoryService memoryService,
                                         ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.decisionRepository = decisionRepository;
        this.leadJourneyService = leadJourneyService;
        this.handoffPolicy = handoffPolicy;
        this.memoryService = memoryService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ConversationGraphRoute commit(ConversationTurnReference reference, AcceptedInboundMessage inbound,
                                         ConversationSnapshot conversation, ConversationMemoryContext memory,
                                         LeadJourneySnapshot journey, com.ford.service.conversations.api.AssistantReply reply) {
        var providerMessageId = inbound.providerMessageId();
        log.info("Conversation commit started: conversationId={} providerMessageId={} handoffRequired={} decisionPresent={} journeyProposalPresent={}",
                reference.conversationId(), providerMessageId, reply.handoffRequired(), reply.decision() != null,
                reply.journeyProposal() != null);
        if (reply.decision() != null) {
            decisionRepository.save(conversation.conversationId(), providerMessageId, reply.decision());
        }
        if (reply.journeyProposal() != null) {
            var sensitive = reply.decision() != null
                    && reply.decision().sensitiveDataLevel() != SensitiveDataLevel.NONE;
            leadJourneyService.apply(reference.tenantId(), reference.conversationId(), journey,
                    reply.journeyProposal(), inbound.text(), sensitive, providerMessageId);
        }

        var customerAuthorized = customerAuthorization.isAuthorized(inbound.text(), memory);
        if (handoffPolicy.shouldCreateHandoff(reference.tenantId(), customerAuthorized)) {
            var payload = payloadFactory.create(inbound.text(), memory, reply.text());
            var decision = reply.decision();
            var urgent = decision.intent() == ConversationIntent.HUMAN_REQUEST
                    || decision.handoffRecommendation() == HandoffRecommendation.USER_REQUESTED
                    || decision.leadTemperature() == LeadTemperature.HOT;
            var reason = payload.reason();
            var accepted = repository.requestHumanHandoff(reference.tenantId(), reference.conversationId(),
                    conversation.version(), providerMessageId, new HandoffDecision(reason,
                            urgent ? "HIGH" : "NORMAL",
                            handoffIdempotencyKey(reference, inbound.providerMessageId())),
                    new ConversationTurn("Registré su solicitud en la fila interna de atención. " +
                            "Aún no tengo confirmación de cuándo podrá atenderle un asesor."));
            if (!accepted) {
                log.warn("Conversation handoff commit rejected: conversationId={} providerMessageId={} reason=optimistic_lock_or_duplicate",
                        reference.conversationId(), providerMessageId);
                return ConversationGraphRoute.IGNORED;
            }
            eventPublisher.publishEvent(new HandoffRequested(UUID.randomUUID(), reference.tenantId(),
                    reference.conversationId(), reason, Instant.now(), payload));
            log.info("Conversation handoff committed: conversationId={} providerMessageId={} reason={} urgent={}",
                    reference.conversationId(), providerMessageId, reason, urgent);
            return ConversationGraphRoute.HUMAN_PENDING;
        }

        var handoffEnabled = handoffPolicy.enabled(reference.tenantId());
        var shouldOffer = handoffEnabled
                && handoffOfferPolicy.shouldOffer(inbound.text(), reply.text(),
                        reply.decision() == null ? LeadTemperature.UNKNOWN : reply.decision().leadTemperature(), memory);
        var text = customerAuthorized && !handoffEnabled ? HANDOFF_UNAVAILABLE_TEXT
                : reply.handoffRequired() ? shouldOffer ? handoffOfferPolicy.offerAfter(null) : UNAUTHORIZED_HANDOFF_TEXT
                : shouldOffer ? handoffOfferPolicy.offerAfter(reply.text()) : reply.text();
        if (!repository.completeBotTurn(reference.tenantId(), reference.conversationId(), conversation.version(),
                providerMessageId, new ConversationTurn(text))) {
            log.warn("Conversation bot commit rejected: conversationId={} providerMessageId={} reason=optimistic_lock_or_duplicate",
                    reference.conversationId(), providerMessageId);
            return ConversationGraphRoute.IGNORED;
        }
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
        return ConversationGraphRoute.BOT_REPLY;
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
}
