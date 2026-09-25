package com.ford.service.conversations.domain;

import java.time.Instant;

public record AcceptedInboundTurn(String providerMessageId, String participantId,
                                  String text, Instant receivedAt) {
}
