package com.ford.service.channels.application.port.in;

import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.OutboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import com.ford.service.channels.domain.DeliveryResult;

import java.util.List;

public interface ChannelAdapter {
    Channel channel();

    InboundMessage parseInbound(RawWebhook payload);

    default List<InboundMessage> parseInboundMessages(RawWebhook payload) {
        return List.of(parseInbound(payload));
    }

    void validateWebhook(WebhookRequest request);

    default void validateWebhook(WebhookRequest request, String secretReference) {
        validateWebhook(request);
    }

    default String providerAccountId(String body) {
        throw new UnsupportedOperationException("Provider account validation is not implemented");
    }

    default List<String> providerAccountIds(String body) {
        try {
            return List.of(providerAccountId(body));
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }

    default List<InboundMessage> parseInboundMessages(RawWebhook payload, String providerAccountId) {
        return parseInboundMessages(payload).stream()
                .filter(message -> providerAccountId.equals(message.providerAccountId()))
                .toList();
    }

    default boolean isStatusCallback(String body) {
        return false;
    }

    default DeliveryResult sendReply(OutboundMessage message) {
        throw new UnsupportedOperationException("Outbound delivery is provided by a channel delivery port");
    }
}
