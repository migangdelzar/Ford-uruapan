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

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "true")
public class DefaultConversationTurnProcessor implements ConversationTurnProcessor {

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
        var inbound = inboundRepository.find(reference.tenantId(), reference.conversationId(), reference.receiptId())
                .orElse(null);
        if (inbound == null || conversationRepository.turnAlreadyCompleted(reference.tenantId(),
                reference.conversationId(), inbound.providerMessageId())) {
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        }

        var conversation = conversationRepository.find(reference.tenantId(), reference.conversationId())
                .filter(snapshot -> snapshot.tenantId().equals(reference.tenantId()))
                .orElse(null);
        if (conversation == null || !canUseAssistant(conversation.state())) {
            return new ConversationTurnOutcome(ConversationGraphRoute.IGNORED);
        }

        var memoryKey = new ConversationMemoryKey(reference.tenantId(), conversation.channel(),
                conversation.providerAccountId(), inbound.participantId(), reference.conversationId());
        memoryService.recordInbound(memoryKey, reference.receiptId(), inbound.providerMessageId(),
                inbound.text(), inbound.receivedAt());
        var memory = memoryService.load(new MemoryQuery(memoryKey, inbound.text(), inbound.providerMessageId()));
        var journey = leadJourneyService.load(reference.tenantId(), reference.conversationId());
        var reply = assistant.respond(new ConversationTurnRequest(reference.tenantId(),
                reference.conversationId(), inbound.text(), conversation.state(), memory, journey));
        return new ConversationTurnOutcome(commitService.commit(reference, inbound, conversation,
                memory, journey, reply));
    }

    private static boolean canUseAssistant(ConversationState state) {
        return state == ConversationState.BOT_ACTIVE || state == ConversationState.LEAD_CAPTURE;
    }
}
