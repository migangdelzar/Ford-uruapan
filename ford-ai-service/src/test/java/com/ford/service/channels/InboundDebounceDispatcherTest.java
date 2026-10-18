package com.ford.service.channels;

import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.api.InboundTurnReleased;
import com.ford.service.channels.application.InboundDebounceDispatcher;
import com.ford.service.channels.application.InboundDebounceProperties;
import com.ford.service.channels.application.port.out.InboundDebounceRepository;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboundDebounceDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");

    @Test
    void publishesAReleasedTurnForEachDueConversation() {
        var repository = mock(InboundDebounceRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var event = new InboundMessageAccepted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Channel.WHATSAPP, "wamid.1", NOW.minusSeconds(5));
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(event));

        new InboundDebounceDispatcher(repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC),
                new InboundDebounceProperties()).dispatchDue();

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(InboundTurnReleased.class);
        assertThat(((InboundTurnReleased) captor.getValue()).providerMessageId()).isEqualTo("wamid.1");
    }

    @Test
    void publishesNothingWhenNoConversationIsDue() {
        var repository = mock(InboundDebounceRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of());

        new InboundDebounceDispatcher(repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC),
                new InboundDebounceProperties()).dispatchDue();

        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
