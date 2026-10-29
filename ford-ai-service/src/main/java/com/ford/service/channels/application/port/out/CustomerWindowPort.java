package com.ford.service.channels.application.port.out;

import com.ford.service.channels.domain.Channel;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CustomerWindowPort {
    Optional<CustomerWindow> find(UUID tenantId, UUID conversationId);

    record CustomerWindow(UUID tenantId, UUID conversationId, Channel channel,
                          Instant lastInboundAt, Instant expiresAt) {
        public boolean open(Instant now) {
            return expiresAt != null && now.isBefore(expiresAt);
        }
    }
}
