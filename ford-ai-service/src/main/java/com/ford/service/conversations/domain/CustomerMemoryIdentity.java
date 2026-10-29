package com.ford.service.conversations.domain;

import com.ford.service.channels.domain.Channel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustomerMemoryIdentity(UUID id, UUID tenantId, Channel channel,
                                     Instant factsForgetBefore, Instant createdAt) {
    public CustomerMemoryIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
