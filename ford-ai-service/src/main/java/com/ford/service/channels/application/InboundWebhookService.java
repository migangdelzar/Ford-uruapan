package com.ford.service.channels.application;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class InboundWebhookService {

    private final ChannelBindingService bindingService;
    private final ChannelMessageService messageService;

    public InboundWebhookService(ChannelBindingService bindingService,
                                 ChannelMessageService messageService) {
        this.bindingService = Objects.requireNonNull(bindingService);
        this.messageService = Objects.requireNonNull(messageService);
    }

    public void accept(Channel channel, String signature, String body,
                       ChannelAdapter adapter, String appSecret) {
        var request = new WebhookRequest(body, signature == null
                ? Map.of()
                : Map.of("X-Hub-Signature-256", signature));
        adapter.validateWebhook(request, appSecret);
        var providerAccountIds = adapter.providerAccountIds(body);
        if (providerAccountIds.isEmpty()) {
            return;
        }
        var rawPayloadReference = "meta:" + channel.name().toLowerCase() + ":" + UUID.randomUUID();
        var messages = new ArrayList<InboundMessage>();
        for (var providerAccountId : providerAccountIds) {
            var binding = bindingService.resolve(channel, providerAccountId);
            var webhook = RawWebhook.fromBinding(binding, body, rawPayloadReference);
            messages.addAll(adapter.parseInboundMessages(webhook, providerAccountId));
        }
        messageService.acceptAll(messages);
    }
}
