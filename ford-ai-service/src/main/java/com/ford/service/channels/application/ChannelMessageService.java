package com.ford.service.channels.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class ChannelMessageService {

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
    private final InboundMessageDeduplicationPort deduplication;
    private final InboundMessagePersistencePort persistence;
    private final ApplicationEventPublisher eventPublisher;

    public ChannelMessageService() {
        this.deduplication = null;
        this.persistence = null;
        this.eventPublisher = null;
    }

    @Autowired
    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence,
                                 ApplicationEventPublisher eventPublisher) {
        this.deduplication = deduplication;
        this.persistence = persistence;
        this.eventPublisher = eventPublisher;
    }

    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence) {
        this.deduplication = deduplication;
        this.persistence = persistence;
        this.eventPublisher = null;
    }

    @Transactional
    public InboundMessage accept(RawWebhook webhook, ChannelAdapter adapter) {
        var message = adapter.parseInbound(webhook);
        var key = message.tenantId() + ":" + message.channel() + ":" + message.providerMessageId();
        var receiptId = UUID.randomUUID();
        var firstDelivery = deduplication == null
                ? processedMessageIds.add(key)
                : deduplication.recordIfNew(message, receiptId);
        if (!firstDelivery) {
            return null;
        }
        var conversationId = message.conversationId();
        if (persistence != null) {
            conversationId = persistence.persist(message, receiptId);
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new InboundMessageAccepted(
                    receiptId, message.tenantId(), conversationId, message.channel(),
                    message.providerMessageId(), message.receivedAt()));
        }
        return message;
    }
}
