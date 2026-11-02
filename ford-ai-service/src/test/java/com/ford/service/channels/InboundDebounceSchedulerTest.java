package com.ford.service.channels;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.InboundDebounceScheduler;
import com.ford.service.channels.application.InboundDebounceService;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InboundDebounceSchedulerTest {

    @Test
    void schedulesADurableDebounceForEveryAcceptedInbound() {
        var service = mock(InboundDebounceService.class);
        var event = new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Channel.WHATSAPP, "wamid.1", Instant.parse("2026-10-17T18:00:00Z"));

        new InboundDebounceScheduler(service).on(event);

        verify(service).schedule(event);
    }
}
