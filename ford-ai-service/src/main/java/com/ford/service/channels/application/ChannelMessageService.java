package com.ford.service.channels.application;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public final class ChannelMessageService {

    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    public InboundMessage accept(RawWebhook webhook, ChannelAdapter adapter) {
        var message = adapter.parseInbound(webhook);
        var key = message.channel() + ":" + message.providerMessageId();
        if (!processedMessageIds.add(key)) {
            return null;
        }
        return message;
    }
}
