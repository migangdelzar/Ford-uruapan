package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.shared.HumanHandoffPolicy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.time.Instant;
import java.util.UUID;

public class ConversationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ConversationWorkflow.class);

    private final ConversationRepository repository;
    private final ConversationAssistant assistant;
    private final ApplicationEventPublisher eventPublisher;
    private final HumanHandoffPolicy handoffPolicy;
    private final ConversationMemoryService memoryService;
    private final ConversationDecisionRepository decisionRepository;

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService) {
        this(repository, assistant, eventPublisher, handoffPolicy, memoryService,
                (conversationId, providerMessageId, decision) -> { });
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy,
                                ConversationMemoryService memoryService,
                                ConversationDecisionRepository decisionRepository) {
        this.repository = Objects.requireNonNull(repository);
        this.assistant = Objects.requireNonNull(assistant);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
        this.memoryService = Objects.requireNonNull(memoryService);
        this.decisionRepository = Objects.requireNonNull(decisionRepository);
    }

    @ApplicationModuleListener
    public void on(InboundMessageAccepted event) {
        var snapshot = repository.find(event.conversationId()).orElse(null);
        if (snapshot == null || !canUseAssistant(snapshot.state())) {
            return;
        }

        var memoryKey = new ConversationMemoryKey(snapshot.tenantId(), snapshot.channel(),
                snapshot.providerAccountId(), snapshot.recipientId(), snapshot.conversationId());
        var memory = memoryService.load(new MemoryQuery(memoryKey, snapshot.inboundText(),
                snapshot.providerMessageId()));
        var reply = assistant.respond(new ConversationTurnRequest(
                snapshot.tenantId(), snapshot.conversationId(), snapshot.inboundText(), snapshot.state(), memory));
        try {
            decisionRepository.save(snapshot.conversationId(), snapshot.providerMessageId(), reply.decision());
        } catch (RuntimeException exception) {
            log.warn("Conversation decision persistence failed: conversationId={} providerMessageId={} exceptionType={}",
                    snapshot.conversationId(), snapshot.providerMessageId(), exception.getClass().getSimpleName());
        }

        if (reply.handoffRequired() && handoffPolicy.shouldCreateHandoff(true)) {
            var accepted = repository.requestHumanHandoff(snapshot.conversationId(), snapshot.version(),
                    new HandoffDecision("assistant_requested_handoff"));
            if (accepted) {
                eventPublisher.publishEvent(new HandoffRequested(
                        UUID.randomUUID(), snapshot.tenantId(), snapshot.conversationId(),
                        "assistant_requested_handoff", Instant.now()));
            }
            return;
        }

        var text = reply.handoffRequired() ? handoffPolicy.fallbackText() : reply.text();
        var accepted = repository.completeBotTurn(snapshot.conversationId(), snapshot.version(),
                new ConversationTurn(text));
        if (accepted) {
            eventPublisher.publishEvent(new OutboundDeliveryRequested(
                    snapshot.tenantId(), snapshot.conversationId(), snapshot.channel(),
                    snapshot.recipientId(), text));
        }
    }

    private static boolean canUseAssistant(ConversationState state) {
        return state == ConversationState.BOT_ACTIVE || state == ConversationState.LEAD_CAPTURE;
    }
}
