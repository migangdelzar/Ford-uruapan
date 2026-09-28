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
        when(adapter.providerAccountId("status-body")).thenReturn("phone-42");
        when(adapter.isStatusCallback("status-body")).thenReturn(true);
        when(bindingService.resolve(Channel.WHATSAPP, "phone-42")).thenReturn(binding);

        new InboundWebhookService(bindingService, messageService)
                .accept(Channel.WHATSAPP, "sha256=valid", "status-body", adapter, "app-secret");

        verify(adapter).validateWebhook(any(), eq("app-secret"));
        verify(bindingService).resolve(Channel.WHATSAPP, "phone-42");
        verifyNoInteractions(messageService);
    }

    @Test
    void routesNonStatusWebhookThroughBatchAcceptance() {
        var bindingService = mock(ChannelBindingService.class);
        var messageService = mock(ChannelMessageService.class);
        var adapter = mock(ChannelAdapter.class);
        var binding = new ChannelBinding(UUID.randomUUID(), UUID.randomUUID(), Channel.WHATSAPP,
                "phone-42", "app-secret", true);
        when(adapter.providerAccountId("message-body")).thenReturn("phone-42");
        when(bindingService.resolve(Channel.WHATSAPP, "phone-42")).thenReturn(binding);

        new InboundWebhookService(bindingService, messageService)
                .accept(Channel.WHATSAPP, "sha256=valid", "message-body", adapter, "app-secret");

        verify(messageService).acceptAll(any(), eq(adapter));
        verify(messageService, never()).accept(any(), eq(adapter));
    }
}
