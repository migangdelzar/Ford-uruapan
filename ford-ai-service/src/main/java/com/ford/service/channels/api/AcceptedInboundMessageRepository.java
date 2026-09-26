package com.ford.service.channels.api;

import org.springframework.modulith.NamedInterface;

import java.util.Optional;
import java.util.UUID;

@NamedInterface
public interface AcceptedInboundMessageRepository {
    Optional<AcceptedInboundMessage> find(UUID tenantId, UUID conversationId, UUID receiptId);
}
