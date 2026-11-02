package com.ford.service.channels.application.port.out;

import com.ford.service.channels.domain.InboundMessage;

import java.util.UUID;

public interface InboundMessagePersistencePort {
    UUID persist(InboundMessage message);

    default UUID persist(InboundMessage message, UUID receiptId) {
        return persist(message);
    }
}
