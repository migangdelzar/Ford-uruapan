package com.ford.service.channels.api;

import java.time.Instant;
import java.util.List;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessageKind;

public record AcceptedInboundMessage(String providerMessageId, String participantId,
                                     String text, Instant receivedAt, InboundMessageKind kind,
                                     List<InboundAttachment> attachments) {
    public AcceptedInboundMessage(String providerMessageId, String participantId,
                                  String text, Instant receivedAt) {
        this(providerMessageId, participantId, text, receivedAt, InboundMessageKind.TEXT, List.of());
    }

    public AcceptedInboundMessage {
        kind = kind == null ? InboundMessageKind.TEXT : kind;
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
    }
}
