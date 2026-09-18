package com.ford.service.channels.domain;

import java.util.UUID;

public record RawWebhook(UUID tenantId, String body, String rawPayloadReference) {
}
