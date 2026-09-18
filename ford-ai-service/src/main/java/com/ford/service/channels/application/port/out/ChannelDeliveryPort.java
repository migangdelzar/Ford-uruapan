package com.ford.service.channels.application.port.out;

import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.OutboundMessage;

public interface ChannelDeliveryPort {
    DeliveryResult send(OutboundMessage message);
}
