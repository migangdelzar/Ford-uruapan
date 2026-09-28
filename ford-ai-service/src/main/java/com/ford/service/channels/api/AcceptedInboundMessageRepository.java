package com.ford.service.channels.api;

import org.springframework.modulith.NamedInterface;

import java.util.Optional;
import java.util.UUID;

@NamedInterface
public interface AcceptedInboundMessageRepository {
    Optional<AcceptedInboundMessage> find(UUID tenantId, UUID conversationId, UUID receiptId);

    default void persistResolvedContent(UUID tenantId, UUID conversationId, UUID receiptId, String text) {
        throw new UnsupportedOperationException("Resolved inbound content persistence is not implemented");
    }
}
