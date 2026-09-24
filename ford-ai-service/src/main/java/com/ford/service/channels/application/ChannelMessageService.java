package com.ford.service.channels.application;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.InboundMessageAccepted;
import com.ford.service.channels.domain.RawWebhook;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelMessageService {

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
    private final InboundMessageDeduplicationPort deduplication;
    private final InboundMessagePersistencePort persistence;
    private final ApplicationEventPublisher events;

    public ChannelMessageService() {
        this(null, null, event -> { });
    }

    public ChannelMessageService(ApplicationEventPublisher events) {
        this(null, null, events);
    }

    @Autowired
    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence,
                                 ApplicationEventPublisher events) {
        this.deduplication = deduplication;
        this.persistence = persistence;
        this.events = events;
    }

    @Transactional
    public InboundMessage accept(RawWebhook webhook, ChannelAdapter adapter) {
        var message = adapter.parseInbound(webhook);
        var key = message.tenantId() + ":" + message.channel() + ":" + message.providerMessageId();
        var firstDelivery = deduplication == null ? processedMessageIds.add(key) : deduplication.recordIfNew(message);
        if (!firstDelivery) {
            return null;
        }
        if (persistence != null) {
            persistence.persist(message);
        }
        events.publishEvent(new InboundMessageAccepted(message));
        return message;
    }
}
