package com.ford.service.channels;

import com.ford.service.channels.api.AcceptedInboundMessage;
import com.ford.service.channels.api.AcceptedInboundMessageRepository;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.InboundDebounceService;
import com.ford.service.channels.application.port.out.InboundDebounceRepository;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InboundDebounceServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");

    @Test
    void ordinaryMessageSchedulesFiveSecondsAfterReceipt() {
        var repository = mock(InboundDebounceRepository.class);
        var messages = mock(AcceptedInboundMessageRepository.class);
        var event = event();
        when(messages.find(event.tenantId(), event.conversationId(), event.receiptId()))
                .thenReturn(Optional.of(new AcceptedInboundMessage(event.providerMessageId(), "p", "¿Precio de Ranger?", NOW)));

        new InboundDebounceService(repository, messages, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5))
                .schedule(event);

        verify(repository).schedule(event, NOW.plusSeconds(5));
    }

    @Test
    void humanRequestAndOptOutAndUrgentComplaintBypassDelay() {
        for (var text : new String[]{"Quiero hablar con un asesor", "No me escriban más", "Queja urgente por un cobro"}) {
            var repository = mock(InboundDebounceRepository.class);
            var messages = mock(AcceptedInboundMessageRepository.class);
            var event = event();
            when(messages.find(event.tenantId(), event.conversationId(), event.receiptId()))
                    .thenReturn(Optional.of(new AcceptedInboundMessage(event.providerMessageId(), "p", text, NOW)));

            new InboundDebounceService(repository, messages, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5))
                    .schedule(event);

            verify(repository).schedule(event, NOW);
        }
    }

    @Test
    void missingDurableReceiptStopsScheduling() {
        var repository = mock(InboundDebounceRepository.class);
        var messages = mock(AcceptedInboundMessageRepository.class);
        var event = event();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new InboundDebounceService(repository, messages, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5))
                        .schedule(event)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    private static InboundMessageAccepted event() {
        return new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Channel.WHATSAPP, "wamid.1", NOW);
    }
}
