package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.leads.application.LeadJourneyService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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

    public DefaultConversationTurnProcessor(AcceptedInboundMessageRepository inboundRepository,
                                            ConversationRepository conversationRepository,
                                            ConversationMemoryService memoryService,
                                            LeadJourneyService leadJourneyService,
                                            ConversationAssistant assistant,
                                            ConversationTurnCommitService commitService) {
        this.inboundRepository = Objects.requireNonNull(inboundRepository);
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.memoryService = Objects.requireNonNull(memoryService);
        this.leadJourneyService = Objects.requireNonNull(leadJourneyService);
        this.assistant = Objects.requireNonNull(assistant);
        this.commitService = Objects.requireNonNull(commitService);
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

        var memoryKey = new ConversationMemoryKey(reference.tenantId(), conversation.channel(),
                conversation.providerAccountId(), inbound.participantId(), reference.conversationId());
        memoryService.recordInbound(memoryKey, reference.receiptId(), inbound.providerMessageId(),
                inbound.text(), inbound.receivedAt());
        var memory = memoryService.load(new MemoryQuery(memoryKey, inbound.text(), inbound.providerMessageId()));
        var journey = leadJourneyService.load(reference.tenantId(), reference.conversationId());
        log.info("Conversation turn invoking assistant: conversationId={} memoryVersion={} recentMessages={} journeyPresent={}",
                reference.conversationId(), memory.memoryVersion(), memory.recentMessages().size(), journey != null);
        var reply = assistant.respond(new ConversationTurnRequest(reference.tenantId(),
                reference.conversationId(), inbound.text(), conversation.state(), memory, journey));
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
