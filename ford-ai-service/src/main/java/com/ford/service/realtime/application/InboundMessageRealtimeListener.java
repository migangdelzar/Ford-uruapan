package com.ford.service.realtime.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
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
    private final AcceptedInboundMessageRepository inboundTurns;

    public InboundMessageRealtimeListener(RealtimeEventHub hub, AcceptedInboundMessageRepository inboundTurns) {
        this.hub = hub;
        this.inboundTurns = inboundTurns;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(InboundMessageAccepted accepted) {
        var data = new LinkedHashMap<String, Object>();
        data.put("providerMessageId", accepted.providerMessageId());
        data.put("channel", accepted.channel().name());
        var inbound = inboundTurns.find(accepted.tenantId(), accepted.conversationId(), accepted.receiptId())
                .orElse(null);
        if (inbound != null && inbound.participantId() != null) {
            data.put("participantId", inbound.participantId());
        }
        if (inbound != null && inbound.text() != null) {
            data.put("text", inbound.text());
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
