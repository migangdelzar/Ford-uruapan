package com.ford.service.realtime.application;

import com.ford.service.channels.domain.InboundMessageAccepted;
import com.ford.service.realtime.domain.FrontendEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class InboundMessageRealtimeListener {

    private final RealtimeEventHub hub;

    public InboundMessageRealtimeListener(RealtimeEventHub hub) {
        this.hub = hub;
    }

    @ApplicationModuleListener
    public void on(InboundMessageAccepted accepted) {
        var message = accepted.message();
        hub.publish(new FrontendEvent(
                UUID.randomUUID(),
                message.tenantId(),
                null,
                "message.created",
                Instant.now(),
                Map.of(
                        "providerMessageId", message.providerMessageId(),
                        "channel", message.channel().name(),
                        "participantId", message.participantId(),
                        "text", message.text(),
                        "receivedAt", message.receivedAt().toString())));
    }
}
