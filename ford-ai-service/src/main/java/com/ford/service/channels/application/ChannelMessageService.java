package com.ford.service.channels.application;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.application.port.out.InboundMessageDeduplicationPort;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public final class ChannelMessageService {

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
    private final InboundMessageDeduplicationPort deduplication;

    public ChannelMessageService() {
        this.deduplication = null;
    }

    @Autowired
    public ChannelMessageService(InboundMessageDeduplicationPort deduplication) {
        this.deduplication = deduplication;
    }

    public InboundMessage accept(RawWebhook webhook, ChannelAdapter adapter) {
        var message = adapter.parseInbound(webhook);
        var key = message.tenantId() + ":" + message.channel() + ":" + message.providerMessageId();
        var firstDelivery = deduplication == null ? processedMessageIds.add(key) : deduplication.recordIfNew(message);
        if (!firstDelivery) {
            return null;
        }
        return message;
    }
}
