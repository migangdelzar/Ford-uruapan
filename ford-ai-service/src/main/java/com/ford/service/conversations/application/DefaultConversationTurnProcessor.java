package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.ai.application.InboundContentResolutionService;
import com.ford.service.ai.application.InboundContentStatus;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.port.out.ShadowDraftRecorder;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.leads.application.LeadJourneyService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "true")
public class DefaultConversationTurnProcessor implements ConversationTurnProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationTurnProcessor.class);

    private final AcceptedInboundMessageRepository inboundRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemoryService memoryService;
    private final LeadJourneyService leadJourneyService;
    private final ConversationAssistant assistant;
    private final ConversationTurnCommitService commitService;
    private final InboundContentResolutionService contentResolutionService;
    private final DealershipResponsePlanner responsePlanner;
    private final ShadowDraftRecorder shadowRecorder;
    private final ShadowReviewGate shadowGate;

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, null, ShadowDraftRecorder.disabled(), ShadowReviewGate.disabled());
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            DealershipResponsePlanner responsePlanner) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, responsePlanner,
                ShadowDraftRecorder.disabled(), ShadowReviewGate.disabled());
    }

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            ShadowDraftRecorder shadowRecorder,
                                            ShadowReviewGate shadowGate) {
        this(inboundRepository, conversationRepository, memoryService, leadJourneyService, assistant,
                commitService, contentResolutionService, null, shadowRecorder, shadowGate);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService,
                                            InboundContentResolutionService contentResolutionService,
                                            DealershipResponsePlanner responsePlanner,
                                            ShadowDraftRecorder shadowRecorder,
                                            ShadowReviewGate shadowGate) {
        this.inboundRepository = Objects.requireNonNull(inboundRepository);
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.memoryService = Objects.requireNonNull(memoryService);
        this.leadJourneyService = Objects.requireNonNull(leadJourneyService);
        this.assistant = Objects.requireNonNull(assistant);
        this.commitService = Objects.requireNonNull(commitService);
        this.contentResolutionService = Objects.requireNonNull(contentResolutionService);
        this.responsePlanner = responsePlanner;
        this.shadowRecorder = Objects.requireNonNull(shadowRecorder);
        this.shadowGate = Objects.requireNonNull(shadowGate);
    }

    @Override
    public ConversationTurnOutcome process(ConversationTurnReference reference) {
        var started = System.nanoTime();
        log.info("Conversation turn started: conversationId={} receiptId={}",
                reference.conversationId(), reference.receiptId());
        var inbound = inboundRepository.find(reference.tenantId(), reference.conversationId(), reference.receiptId())
                .orElse(null);
        if (inbound == null || conversationRepository.turnAlreadyCompleted(reference.tenantId(),
                reference.conversationId(), inbound.providerMessageId())) {
            log.info("Conversation turn skipped: conversationId={} receiptId={} reason={}",
                    reference.conversationId(), reference.receiptId(), inbound == null ? "inbound_not_found" : "turn_already_completed");
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        }

        var conversation = conversationRepository.find(reference.tenantId(), reference.conversationId())
                .filter(snapshot -> snapshot.tenantId().equals(reference.tenantId()))
                .orElse(null);
        if (conversation == null || !canUseAssistant(conversation.state())) {
            log.info("Conversation turn skipped: conversationId={} receiptId={} reason={} state={}",
                    reference.conversationId(), reference.receiptId(), conversation == null ? "conversation_not_found" : "assistant_not_allowed",
                    conversation == null ? "UNKNOWN" : conversation.state());
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        }

        log.info("Conversation turn context loaded: conversationId={} state={} channel={} providerAccountId={}",
                reference.conversationId(), conversation.state(), conversation.channel(), conversation.providerAccountId());

        var resolved = contentResolutionService.resolve(inbound, reference.tenantId(), reference.conversationId());
        if (resolved.status() == InboundContentStatus.READY
                && (inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.IMAGE
                || inbound.kind() == com.ford.service.channels.domain.InboundMessageKind.AUDIO)) {
            inboundRepository.persistResolvedContent(reference.tenantId(), reference.conversationId(),
                    reference.receiptId(), resolved.text());
        }

        var memoryKey = new ConversationMemoryKey(reference.tenantId(), conversation.channel(),
                conversation.providerAccountId(), inbound.participantId(), reference.conversationId());
        memoryService.recordInbound(memoryKey, reference.receiptId(), inbound.providerMessageId(),
                resolved.text(), inbound.receivedAt());
        var memory = memoryService.load(new MemoryQuery(memoryKey, resolved.text(), inbound.providerMessageId()));
        var journey = leadJourneyService.load(reference.tenantId(), reference.conversationId());
        if (shadowGate.killSwitchEnabled()) {
            log.info("Shadow review kill switch active; routing turn to human queue: conversationId={} receiptId={}",
                    reference.conversationId(), reference.receiptId());
            var route = commitService.forceHumanHandoff(reference, inbound, conversation, memory, journey);
            return new ConversationTurnOutcome(route);
        }
        log.info("Conversation turn invoking assistant: conversationId={} memoryVersion={} recentMessages={} journeyPresent={}",
                reference.conversationId(), memory.memoryVersion(), memory.recentMessages().size(), journey != null);
        AssistantReply reply;
        try {
            reply = resolved.status() == InboundContentStatus.CLARIFICATION_REQUIRED
                    ? new AssistantReply(resolved.text(), false)
                    : assistant.respond(new ConversationTurnRequest(reference.tenantId(),
                            reference.conversationId(), resolved.text(), conversation.state(), memory, journey));
        } catch (AssistantTurnUnavailableException unavailable) {
            if (unavailable.disposition() == AssistantTurnUnavailableException.Disposition.RETRY) {
                throw unavailable;
            }
            log.warn("Conversation turn held for review without a customer reply: conversationId={} receiptId={} disposition={}",
                    reference.conversationId(), reference.receiptId(), unavailable.disposition());
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        }
        if (responsePlanner != null) {
            reply = responsePlanner.apply(reply, resolved.text());
        }
        if (shadowGate.shadowEnabled()) {
            shadowRecorder.record(reference.tenantId(), reference.conversationId(), reference.receiptId(),
                    inbound.providerMessageId(), reply.text(), Instant.now());
            log.info("Shadow draft recorded without send: conversationId={} receiptId={} route=SHADOW_DRAFT",
                    reference.conversationId(), reference.receiptId());
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        }
        log.info("Conversation turn assistant completed: conversationId={} handoffRequired={} decisionPresent={} replyChars={}",
                reference.conversationId(), reply.handoffRequired(), reply.decision() != null,
                reply.text() == null ? 0 : reply.text().length());
        var route = commitService.commit(reference, inbound, conversation,
                memory, journey, reply);
        log.info("Conversation turn committed: conversationId={} route={} durationMs={}",
                reference.conversationId(), route, elapsedMs(started));
        return new ConversationTurnOutcome(route);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static boolean canUseAssistant(ConversationState state) {
        return state == ConversationState.BOT_ACTIVE || state == ConversationState.LEAD_CAPTURE;
    }
}
