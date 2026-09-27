package com.ford.service.conversations.application;

import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
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

    private final ConversationRepository repository;
    private final ConversationDecisionRepository decisionRepository;
    private final LeadJourneyService leadJourneyService;
    private final HumanHandoffPolicy handoffPolicy;
    private final ConversationMemoryService memoryService;
    private final ApplicationEventPublisher eventPublisher;

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

        if (handoffPolicy.shouldCreateHandoff(reply.handoffRequired())) {
            var decision = reply.decision();
            var urgent = decision.intent() == ConversationIntent.HUMAN_REQUEST
                    || decision.handoffRecommendation() == HandoffRecommendation.USER_REQUESTED
                    || decision.leadTemperature() == LeadTemperature.HOT;
            var reason = decision.intent() == ConversationIntent.HUMAN_REQUEST
                    || decision.handoffRecommendation() == HandoffRecommendation.USER_REQUESTED
                    ? "user_requested_handoff"
                    : decision.leadTemperature() == LeadTemperature.HOT ? "hot_lead" : "assistant_requested_handoff";
            var accepted = repository.requestHumanHandoff(reference.tenantId(), reference.conversationId(),
                    conversation.version(), providerMessageId, new HandoffDecision(reason,
                            urgent ? "HIGH" : "NORMAL",
                            handoffIdempotencyKey(reference, inbound.providerMessageId())),
                    new ConversationTurn("Ya registré tu solicitud para que un asesor te atienda. " +
                            "La conversación sigue en la fila de atención; te responderán por aquí cuando alguien la tome."));
            if (!accepted) {
                log.warn("Conversation handoff commit rejected: conversationId={} providerMessageId={} reason=optimistic_lock_or_duplicate",
                        reference.conversationId(), providerMessageId);
                return ConversationGraphRoute.IGNORED;
            }
            eventPublisher.publishEvent(new HandoffRequested(UUID.randomUUID(), reference.tenantId(),
                    reference.conversationId(), reason, Instant.now()));
            log.info("Conversation handoff committed: conversationId={} providerMessageId={} reason={} urgent={}",
                    reference.conversationId(), providerMessageId, reason, urgent);
            return ConversationGraphRoute.HUMAN_PENDING;
        }

        var text = reply.handoffRequired() ? handoffPolicy.fallbackText() : reply.text();
        if (!repository.completeBotTurn(reference.tenantId(), reference.conversationId(), conversation.version(),
                providerMessageId, new ConversationTurn(text))) {
            log.warn("Conversation bot commit rejected: conversationId={} providerMessageId={} reason=optimistic_lock_or_duplicate",
                    reference.conversationId(), providerMessageId);
            return ConversationGraphRoute.IGNORED;
        }
        var key = memory.key();
        memoryService.recordOutbound(key, outboundMessageId(providerMessageId),
                "outbound:" + providerMessageId, text, Instant.now());
        eventPublisher.publishEvent(new ConversationMemoryProjectionRefreshRequested(
                key, memoryService.project(memory, inbound.text(), reference.receiptId()), memory.memoryVersion()));
        memoryService.invalidate(key);
        eventPublisher.publishEvent(new OutboundDeliveryRequested(reference.tenantId(),
                reference.conversationId(), conversation.channel(), conversation.recipientId(), text));
        var temperature = reply.decision() == null || reply.decision().leadTemperature() == null
                ? "UNKNOWN" : reply.decision().leadTemperature().name();
        eventPublisher.publishEvent(new com.ford.service.shared.ConversationTurnCompleted(
                reference.tenantId(), reference.conversationId(), providerMessageId,
                temperature, inbound.text(), reply.text()));
        log.info("Conversation bot reply committed: conversationId={} providerMessageId={} route=BOT_REPLY outboundRequested=true leadTemperature={}",
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
