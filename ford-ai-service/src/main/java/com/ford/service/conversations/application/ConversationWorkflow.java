package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.conversations.api.ConversationTurnRequest;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.api.OutboundDeliveryRequested;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationState;
import com.ford.service.conversations.domain.ConversationTurn;
import com.ford.service.conversations.domain.HandoffDecision;
import com.ford.service.shared.HumanHandoffPolicy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;

import java.util.Objects;
import java.time.Instant;
import java.util.UUID;

public class ConversationWorkflow {

    private final ConversationRepository repository;
    private final ConversationAssistant assistant;
    private final ApplicationEventPublisher eventPublisher;
    private final HumanHandoffPolicy handoffPolicy;

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher) {
        this(repository, assistant, eventPublisher, new HumanHandoffPolicy(true));
    }

    public ConversationWorkflow(ConversationRepository repository,
                                ConversationAssistant assistant,
                                ApplicationEventPublisher eventPublisher,
                                HumanHandoffPolicy handoffPolicy) {
        this.repository = Objects.requireNonNull(repository);
        this.assistant = Objects.requireNonNull(assistant);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy);
    }

    @ApplicationModuleListener
    public void on(InboundMessageAccepted event) {
        var snapshot = repository.find(event.conversationId()).orElse(null);
        if (snapshot == null || !canUseAssistant(snapshot.state())) {
            return;
        }

        var reply = assistant.respond(new ConversationTurnRequest(
                snapshot.tenantId(), snapshot.conversationId(), snapshot.inboundText(), snapshot.state()));

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
