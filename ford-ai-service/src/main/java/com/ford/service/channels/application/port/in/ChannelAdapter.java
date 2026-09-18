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

    default DeliveryResult sendReply(OutboundMessage message) {
        throw new UnsupportedOperationException("Outbound delivery is provided by a channel delivery port");
    }
}
