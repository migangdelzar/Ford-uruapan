package com.ford.service.channels.application.port.out;

import com.ford.service.channels.application.port.in.TemplateMessageCommand;

import java.util.UUID;

public interface TemplateMessagePort {
    TemplateReceipt send(UUID tenantId, UUID conversationId, TemplateMessageCommand command,
                         String idempotencyKey);

    record TemplateReceipt(String providerMessageId, boolean accepted) { }
}
