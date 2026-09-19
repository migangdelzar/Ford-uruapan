package com.ford.service.channels.application.port.out;

import com.ford.service.channels.domain.InboundMessage;

public interface InboundMessageDeduplicationPort {
    boolean recordIfNew(InboundMessage message);
}
