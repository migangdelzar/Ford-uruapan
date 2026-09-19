package com.ford.service.channels.application.port.in;

import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.OutboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import com.ford.service.channels.domain.DeliveryResult;

public interface ChannelAdapter {
    Channel channel();

    InboundMessage parseInbound(RawWebhook payload);

    void validateWebhook(WebhookRequest request);

    default void validateWebhook(WebhookRequest request, String secretReference) {
        validateWebhook(request);
    }

    default String providerAccountId(RawWebhook payload) {
        throw new UnsupportedOperationException("Provider account validation is not implemented");
    }

    default DeliveryResult sendReply(OutboundMessage message) {
        throw new UnsupportedOperationException("Outbound delivery is provided by a channel delivery port");
    }
}
