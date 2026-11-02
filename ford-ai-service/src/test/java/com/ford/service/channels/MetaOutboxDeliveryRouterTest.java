package com.ford.service.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.meta.MetaOutboxDeliveryRouter;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.application.port.out.CustomerWindowPort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaOutboxDeliveryRouterTest {

    @Test
    void carriesTypedReplyOptionsFromOutboxToDeliveryPort() {
        var port = mock(ChannelDeliveryPort.class);
        when(port.send(org.mockito.ArgumentMatchers.any())).thenReturn(new DeliveryResult(true, "wamid.options", null));
        var router = new MetaOutboxDeliveryRouter(new ObjectMapper(), Map.of(Channel.WHATSAPP, port));
        var option = new ReplyOption("vehicle-0123456789abcdef", "Ranger 2027 XLT");
        var event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "conversation", UUID.randomUUID(),
                "OUTBOUND_DELIVERY_REQUESTED",
                "{\"conversationId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"channel\":\"WHATSAPP\",\"recipientId\":\"recipient-1\","
                        + "\"text\":\"Elija\",\"replyOptions\":[{\"id\":\"vehicle-0123456789abcdef\","
                        + "\"title\":\"Ranger 2027 XLT\"}]}",
                OutboxEvent.Status.PROCESSING, 1, Instant.now(), Instant.now(), Instant.now().plusSeconds(30),
                "key", null, null);

        assertThat(router.deliver(event).accepted()).isTrue();
        verify(port).send(argThat(message -> message.replyOptions().equals(java.util.List.of(option))));
    }

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

    @Test
    void preventsProviderDeliveryWhenCustomerWindowIsMissing() {
        var port = mock(ChannelDeliveryPort.class);
        var windows = mock(CustomerWindowPort.class);
        var conversationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var event = event("key");
        when(windows.find(event.tenantId(), conversationId)).thenReturn(Optional.empty());
        var router = new MetaOutboxDeliveryRouter(new ObjectMapper(), Map.of(Channel.WHATSAPP, port), windows);

        var result = router.deliver(event);

        assertThat(result.accepted()).isFalse();
        assertThat(result.permanent()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(port);
    }

    @Test
    void preventsProviderDeliveryWhenCustomerWindowHasExpired() {
        var port = mock(ChannelDeliveryPort.class);
        var windows = mock(CustomerWindowPort.class);
        var event = event("key");
        var conversationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(windows.find(event.tenantId(), conversationId)).thenReturn(Optional.of(
                new CustomerWindowPort.CustomerWindow(event.tenantId(), conversationId, Channel.WHATSAPP,
                        Instant.now().minus(Duration.ofHours(25)), Instant.now().minus(Duration.ofHours(1)))));
        var router = new MetaOutboxDeliveryRouter(new ObjectMapper(), Map.of(Channel.WHATSAPP, port), windows);

        var result = router.deliver(event);

        assertThat(result.accepted()).isFalse();
        assertThat(result.permanent()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(port);
    }

    @Test
    void allowsProviderDeliveryWhenTheMatchingCustomerWindowIsOpen() {
        var port = mock(ChannelDeliveryPort.class);
        var windows = mock(CustomerWindowPort.class);
        var event = event("key");
        var conversationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(windows.find(event.tenantId(), conversationId)).thenReturn(Optional.of(
                new CustomerWindowPort.CustomerWindow(event.tenantId(), conversationId, Channel.WHATSAPP,
                        Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(23)))));
        when(port.send(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DeliveryResult(true, "wamid.2", null));
        var router = new MetaOutboxDeliveryRouter(new ObjectMapper(), Map.of(Channel.WHATSAPP, port), windows);

        var result = router.deliver(event);

        assertThat(result.accepted()).isTrue();
        verify(port).send(org.mockito.ArgumentMatchers.any());
    }

    private static OutboxEvent event(String idempotencyKey) {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "conversation", UUID.randomUUID(),
                "OUTBOUND_DELIVERY_REQUESTED",
                "{\"conversationId\":\"00000000-0000-0000-0000-000000000001\",\"channel\":\"WHATSAPP\",\"recipientId\":\"recipient-1\",\"text\":\"Hola\"}",
                OutboxEvent.Status.PROCESSING, 1, Instant.now(), Instant.now(), Instant.now().plusSeconds(30),
                idempotencyKey, null, null);
    }
}
