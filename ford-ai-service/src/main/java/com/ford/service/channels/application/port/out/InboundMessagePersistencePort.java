package com.ford.service.channels.application.port.out;

import com.ford.service.channels.domain.InboundMessage;

import java.util.UUID;

public interface InboundMessagePersistencePort {
    void persist(InboundMessage message);

    default void persist(InboundMessage message, UUID receiptId) {
        persist(message);
    }
}
