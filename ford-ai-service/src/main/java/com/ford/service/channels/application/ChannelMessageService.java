package com.ford.service.channels.application;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.application.port.out.InboundMessagePersistencePort;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelMessageService {

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
    private final InboundMessageDeduplicationPort deduplication;
    private final InboundMessagePersistencePort persistence;

    public ChannelMessageService() {
        this.deduplication = null;
        this.persistence = null;
    }

    @Autowired
    public ChannelMessageService(InboundMessageDeduplicationPort deduplication,
                                 InboundMessagePersistencePort persistence) {
        this.deduplication = deduplication;
        this.persistence = persistence;
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
        return message;
    }
}
