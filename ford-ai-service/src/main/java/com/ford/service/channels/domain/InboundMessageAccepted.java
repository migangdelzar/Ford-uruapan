package com.ford.service.channels.domain;

public record InboundMessageAccepted(InboundMessage message) {
    public InboundMessageAccepted {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
