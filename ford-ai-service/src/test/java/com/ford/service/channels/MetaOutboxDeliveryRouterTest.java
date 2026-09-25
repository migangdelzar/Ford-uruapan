package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.meta.MetaOutboxDeliveryRouter;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaOutboxDeliveryRouterTest {

    @Test
    void forwardsStableIdempotencyKeyToTheChannelPort() {
        var port = mock(ChannelDeliveryPort.class);
        when(port.send(argThat(message -> "tenant:conversation:reply".equals(message.idempotencyKey()))))
                .thenReturn(new DeliveryResult(true, "wamid.1", null));
        var router = new MetaOutboxDeliveryRouter(new ObjectMapper(), Map.of(Channel.WHATSAPP, port));
        var event = event("tenant:conversation:reply");

        var result = router.deliver(event);

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("wamid.1");
        verify(port).send(argThat(message -> message.channel() == Channel.WHATSAPP
                && "recipient-1".equals(message.recipientId())));
    }

    @Test
    void rejectsMalformedOrUnsupportedDeliveryAsPermanent() {
        var router = new MetaOutboxDeliveryRouter(new ObjectMapper(), Map.of());
        var event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "conversation", UUID.randomUUID(),
                "OUTBOUND_DELIVERY_REQUESTED", "{\"channel\":\"WHATSAPP\",\"recipientId\":\"r\"}",
                OutboxEvent.Status.PROCESSING, 1, Instant.now(), Instant.now(), Instant.now().plusSeconds(30),
                "key", null, null);

        var result = router.deliver(event);

        assertThat(result.accepted()).isFalse();
        assertThat(result.permanent()).isTrue();
    }

    private static OutboxEvent event(String idempotencyKey) {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "conversation", UUID.randomUUID(),
                "OUTBOUND_DELIVERY_REQUESTED",
                "{\"conversationId\":\"00000000-0000-0000-0000-000000000001\",\"channel\":\"WHATSAPP\",\"recipientId\":\"recipient-1\",\"text\":\"Hola\"}",
                OutboxEvent.Status.PROCESSING, 1, Instant.now(), Instant.now(), Instant.now().plusSeconds(30),
                idempotencyKey, null, null);
    }
}
