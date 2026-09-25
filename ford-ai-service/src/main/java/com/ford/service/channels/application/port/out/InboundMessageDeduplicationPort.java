package com.ford.service.channels.application.port.out;

import com.ford.service.channels.domain.InboundMessage;

import java.util.UUID;

public interface InboundMessageDeduplicationPort {
    boolean recordIfNew(InboundMessage message);

    default boolean recordIfNew(InboundMessage message, UUID receiptId) {
        return recordIfNew(message);
    }
}
