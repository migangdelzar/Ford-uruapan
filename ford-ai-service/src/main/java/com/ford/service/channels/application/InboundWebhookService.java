package com.ford.service.channels.application;

import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import org.springframework.stereotype.Service;

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

    public void accept(Channel channel, String webhookKey, String signature, String body,
                       ChannelAdapter adapter) {
        var binding = bindingService.resolve(webhookKey, channel);
        var request = new WebhookRequest(body, signature == null
                ? Map.of()
                : Map.of("X-Hub-Signature-256", signature));
        adapter.validateWebhook(request, binding.secretReference());

        var webhook = RawWebhook.fromBinding(binding, body,
                "meta:" + channel.name().toLowerCase() + ":" + UUID.randomUUID());
        if (!binding.providerAccountId().equals(adapter.providerAccountId(webhook))) {
            throw new SecurityException("Provider account does not match binding");
        }
        messageService.accept(webhook, adapter);
    }
}
