package com.ford.service.conversations.application;

import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.AcceptedInboundTurn;
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
import com.ford.service.conversations.application.port.out.LeadFollowUpTurnTracker;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "true")
public class ConversationTurnCommitService {

    private final ConversationRepository repository;
    private final ConversationDecisionRepository decisionRepository;
    private final LeadJourneyService leadJourneyService;
    private final HumanHandoffPolicy handoffPolicy;
    private final ConversationMemoryService memoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final LeadFollowUpTurnTracker followUpTracker;

    public ConversationTurnCommitService(ConversationRepository repository,
                                         ConversationDecisionRepository decisionRepository,
                                         LeadJourneyService leadJourneyService,
                                         HumanHandoffPolicy handoffPolicy,
                                         ConversationMemoryService memoryService,
                                         ApplicationEventPublisher eventPublisher,
                                         LeadFollowUpTurnTracker followUpTracker) {
        this.repository = repository;
        this.decisionRepository = decisionRepository;
        this.leadJourneyService = leadJourneyService;
        this.handoffPolicy = handoffPolicy;
        this.memoryService = memoryService;
        this.eventPublisher = eventPublisher;
        this.followUpTracker = followUpTracker;
    }

    @Transactional
    public ConversationGraphRoute commit(ConversationTurnReference reference, AcceptedInboundTurn inbound,
                                         ConversationSnapshot conversation, ConversationMemoryContext memory,
                                         LeadJourneySnapshot journey, com.ford.service.conversations.api.AssistantReply reply) {
        var providerMessageId = inbound.providerMessageId();
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
                return ConversationGraphRoute.IGNORED;
            }
            eventPublisher.publishEvent(new HandoffRequested(UUID.randomUUID(), reference.tenantId(),
                    reference.conversationId(), reason, Instant.now()));
            return ConversationGraphRoute.HUMAN_PENDING;
        }

        var text = reply.handoffRequired() ? handoffPolicy.fallbackText() : reply.text();
        if (!repository.completeBotTurn(reference.tenantId(), reference.conversationId(), conversation.version(),
                providerMessageId, new ConversationTurn(text))) {
            return ConversationGraphRoute.IGNORED;
        }
        var key = memory.key();
        eventPublisher.publishEvent(new ConversationMemoryProjectionRefreshRequested(
                key, memoryService.project(memory, inbound.text(), reference.receiptId()), memory.memoryVersion()));
        memoryService.invalidate(key);
        eventPublisher.publishEvent(new OutboundDeliveryRequested(reference.tenantId(),
                reference.conversationId(), conversation.channel(), conversation.recipientId(), text));
        followUpTracker.processTurn(reference.tenantId(), reference.conversationId(), providerMessageId,
                reply.decision().leadTemperature().name(), inbound.text(), reply.text());
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
}
