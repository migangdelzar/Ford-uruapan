package com.ford.service.channels.domain;

import com.ford.service.channels.api.ChannelBinding;

import java.util.UUID;

public record RawWebhook(UUID tenantId, UUID bindingId, String body, String rawPayloadReference) {

    public RawWebhook(UUID tenantId, String body, String rawPayloadReference) {
        this(tenantId, null, body, rawPayloadReference);
    }

    public static RawWebhook fromBinding(ChannelBinding binding, String body, String rawPayloadReference) {
        return new RawWebhook(binding.tenantId(), binding.id(), body, rawPayloadReference);
    }
}
