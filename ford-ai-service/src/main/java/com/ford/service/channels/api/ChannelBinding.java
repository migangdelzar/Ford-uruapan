package com.ford.service.channels.api;

import com.ford.service.channels.domain.Channel;

import java.util.UUID;

public record ChannelBinding(
        UUID id,
        UUID tenantId,
        Channel channel,
        String providerAccountId,
        String secretReference,
        boolean enabled) {
}
