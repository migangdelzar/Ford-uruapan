package com.ford.service.channels.api;

import java.time.Instant;

public record AcceptedInboundMessage(String providerMessageId, String participantId,
                                     String text, Instant receivedAt) {
}
