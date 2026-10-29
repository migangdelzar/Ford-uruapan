package com.ford.service.channels.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.handoffcontact.api.AlternateContactIntakePort;
import com.ford.service.handoffcontact.api.StagingContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ChannelMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageService.class);

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
    private final InboundMessageDeduplicationPort deduplication;
    private final InboundMessagePersistencePort persistence;
    private final ApplicationEventPublisher eventPublisher;
    private final AlternateContactIntakePort alternateContacts;

    public ChannelMessageService() {
        this.deduplication = null;
        this.persistence = null;
        this.eventPublisher = null;
        this.alternateContacts = null;
    }

    public ChannelMessageService(ApplicationEventPublisher eventPublisher) {
        this.deduplication = null;
        this.persistence = null;
        this.eventPublisher = eventPublisher;
        this.alternateContacts = null;
    }

    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence,
                                 ApplicationEventPublisher eventPublisher) {
        this(deduplication, persistence, eventPublisher, null);
    }

    @Autowired
    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence,
                                 ApplicationEventPublisher eventPublisher,
                                 AlternateContactIntakePort alternateContacts) {
        this.deduplication = deduplication;
        this.persistence = persistence;
        this.eventPublisher = eventPublisher;
        this.alternateContacts = alternateContacts;
    }

    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence) {
        this(deduplication, persistence, null, null);
    }

    @Transactional
    public InboundMessage accept(RawWebhook webhook, ChannelAdapter adapter) {
        var messages = adapter.parseInboundMessages(webhook);
        if (messages.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one inbound message; received " + messages.size());
        }
        return accept(messages.getFirst());
    }

    @Transactional
    public List<InboundMessage> acceptAll(RawWebhook webhook, ChannelAdapter adapter) {
        return acceptAll(adapter.parseInboundMessages(webhook));
    }

    @Transactional
    public List<InboundMessage> acceptAll(List<InboundMessage> messages) {
        return messages.stream()
                .map(this::accept)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private InboundMessage accept(InboundMessage message) {
        var started = System.nanoTime();
        var sanitizedMessage = withText(message,
                alternateContacts == null ? message.text() : alternateContacts.redact(message.text()));
        var key = message.tenantId() + ":" + message.channel() + ":" + message.providerMessageId();
        var receiptId = UUID.randomUUID();
        var firstDelivery = deduplication == null
                ? processedMessageIds.add(key)
                : deduplication.recordIfNew(sanitizedMessage, receiptId);
        if (!firstDelivery) {
            log.info("Inbound message ignored: conversationId={} deliveryAttemptId={} channel={} kind={} outcome=duplicate durationMs={}",
                    message.conversationId(), receiptId, message.channel(), message.kind(), elapsedMs(started));
            return null;
        }
        var conversationId = message.conversationId();
        if (persistence != null) {
            conversationId = persistence.persist(sanitizedMessage, receiptId);
        }
        if (alternateContacts != null && conversationId != null && message.text() != null) {
            alternateContacts.captureAndStage(new StagingContext(message.tenantId(), conversationId, receiptId),
                    message.text(), message.receivedAt());
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new InboundMessageAccepted(
                    receiptId, message.tenantId(), conversationId, message.channel(),
                    message.providerMessageId(), message.receivedAt()));
        }
        log.info("Inbound message accepted: conversationId={} receiptId={} channel={} kind={} outcome=accepted durationMs={}",
                conversationId, receiptId, message.channel(), message.kind(), elapsedMs(started));
        return withConversation(sanitizedMessage, conversationId);
    }

    private static InboundMessage withText(InboundMessage message, String text) {
        return new InboundMessage(message.tenantId(), message.channel(), message.providerAccountId(),
                message.providerMessageId(), message.participantId(), text, message.attachments(),
                message.receivedAt(), message.rawPayloadReference(), message.bindingId(),
                message.conversationId(), message.kind());
    }

    private static InboundMessage withConversation(InboundMessage message, UUID conversationId) {
        return new InboundMessage(message.tenantId(), message.channel(), message.providerAccountId(),
                message.providerMessageId(), message.participantId(), message.text(), message.attachments(),
                message.receivedAt(), message.rawPayloadReference(), message.bindingId(),
                conversationId, message.kind());
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
