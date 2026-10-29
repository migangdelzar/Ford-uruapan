package com.ford.service.channels;

import com.ford.service.channels.api.ChannelBinding;
import com.ford.service.channels.application.ChannelBindingService;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.application.InboundWebhookService;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class InboundWebhookServiceTest {

    @Test
    void acknowledgesAValidatedStatusCallbackWithoutPersistingAnInboundMessage() {
        var bindingService = mock(ChannelBindingService.class);
        var messageService = mock(ChannelMessageService.class);
        var adapter = mock(ChannelAdapter.class);
        var binding = new ChannelBinding(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                "phone-42", "app-secret", true);
        when(adapter.providerAccountIds("status-body")).thenReturn(java.util.List.of());

        new InboundWebhookService(bindingService, messageService)
                .accept(Channel.WHATSAPP, "sha256=valid", "status-body", adapter, "app-secret");

        verify(adapter).validateWebhook(any(), eq("app-secret"));
        verifyNoInteractions(bindingService);
        verifyNoInteractions(messageService);
    }

    @Test
    void routesNonStatusWebhookThroughBatchAcceptance() {
        var bindingService = mock(ChannelBindingService.class);
        var messageService = mock(ChannelMessageService.class);
        var adapter = mock(ChannelAdapter.class);
        var binding = new ChannelBinding(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                "phone-42", "app-secret", true);
        when(adapter.providerAccountIds("message-body")).thenReturn(java.util.List.of("phone-42"));
        when(bindingService.resolve(Channel.WHATSAPP, "phone-42")).thenReturn(binding);

        new InboundWebhookService(bindingService, messageService)
                .accept(Channel.WHATSAPP, "sha256=valid", "message-body", adapter, "app-secret");

        verify(messageService).acceptAll(org.mockito.ArgumentMatchers.<com.ford.service.channels.domain.InboundMessage>anyList());
        verify(messageService, never()).accept(any(), eq(adapter));
    }

    @Test
    void resolvesAndRoutesEachProviderAccountInMixedBatchIndependently() {
        var bindingService = mock(ChannelBindingService.class);
        var messageService = mock(ChannelMessageService.class);
        var adapter = mock(ChannelAdapter.class);
        var bindingOne = new ChannelBinding(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                "phone-1", "secret", true);
        var bindingTwo = new ChannelBinding(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                "phone-2", "secret", true);
        when(adapter.providerAccountIds("mixed-body")).thenReturn(java.util.List.of("phone-1", "phone-2"));
        when(bindingService.resolve(Channel.WHATSAPP, "phone-1")).thenReturn(bindingOne);
        when(bindingService.resolve(Channel.WHATSAPP, "phone-2")).thenReturn(bindingTwo);

        new InboundWebhookService(bindingService, messageService)
                .accept(Channel.WHATSAPP, "sha256=valid", "mixed-body", adapter, "secret");

        verify(bindingService).resolve(Channel.WHATSAPP, "phone-1");
        verify(bindingService).resolve(Channel.WHATSAPP, "phone-2");
        verify(adapter).parseInboundMessages(any(), eq("phone-1"));
        verify(adapter).parseInboundMessages(any(), eq("phone-2"));
        verify(messageService).acceptAll(org.mockito.ArgumentMatchers.<com.ford.service.channels.domain.InboundMessage>anyList());
    }
}
