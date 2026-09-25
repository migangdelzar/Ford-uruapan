package com.ford.service.channels.adapter.out;

import com.ford.service.channels.application.port.in.TemplateMessageCommand;
import com.ford.service.channels.application.port.out.TemplateMessagePort;
import com.ford.service.shared.UpstreamUnavailableException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UnavailableTemplateMessageAdapter implements TemplateMessagePort {
    @Override
    public TemplateReceipt send(UUID tenantId, UUID conversationId, TemplateMessageCommand command,
                                String idempotencyKey) {
        throw new UpstreamUnavailableException("approved Meta template delivery is not configured");
    }
}
