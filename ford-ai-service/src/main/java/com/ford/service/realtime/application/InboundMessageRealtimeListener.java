package com.ford.service.realtime.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.realtime.domain.FrontendEvent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InboundMessageRealtimeListener {

    private final RealtimeEventHub hub;

    public InboundMessageRealtimeListener(RealtimeEventHub hub) {
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(InboundMessageAccepted accepted) {
        var data = new LinkedHashMap<String, Object>();
        data.put("providerMessageId", accepted.providerMessageId());
        data.put("channel", accepted.channel().name());
        if (accepted.participantId() != null) {
            data.put("participantId", accepted.participantId());
        }
        if (accepted.text() != null) {
            data.put("text", accepted.text());
        }
        data.put("receivedAt", accepted.acceptedAt().toString());
        hub.publish(new FrontendEvent(
                UUID.randomUUID(),
                accepted.tenantId(),
                accepted.conversationId(),
                "message.created",
                Instant.now(),
                data));
    }
}
