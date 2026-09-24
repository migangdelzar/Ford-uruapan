package com.ford.service.channels.adapter.in.meta;

import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/webhooks/meta")
public final class MetaWebhookController {

    private final List<ChannelAdapter> adapters;
    private final ChannelMessageService messageService;
    private final MetaWebhookProperties properties;

    public MetaWebhookController(List<ChannelAdapter> adapters,
                                 ChannelMessageService messageService,
                                 MetaWebhookProperties properties) {
        this.adapters = List.copyOf(adapters);
        this.messageService = messageService;
        this.properties = properties;
    }

    @GetMapping("/{channel}")
    public ResponseEntity<String> verify(
            @PathVariable String channel,
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode)
                && properties.getVerifyToken().equals(verifyToken)
                && challenge != null
                && adapterFor(channel) != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.badRequest().build();
    }

    @PostMapping("/{channel}")
    public ResponseEntity<Void> receive(
            @PathVariable String channel,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String body) {
        try {
            var adapter = adapterFor(channel);
            if (adapter == null) {
                return ResponseEntity.notFound().build();
            }
            var headers = new HashMap<String, String>();
            if (signature != null) {
                headers.put("X-Hub-Signature-256", signature);
            }
            adapter.validateWebhook(new WebhookRequest(body, headers));
            messageService.accept(new RawWebhook(properties.getTenantId(), body,
                    "meta:" + channel.toLowerCase(Locale.ROOT) + ":" + UUID.randomUUID()), adapter);
            return ResponseEntity.accepted().build();
        } catch (SecurityException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    private ChannelAdapter adapterFor(String channel) {
        try {
            var requested = Channel.valueOf(channel.toUpperCase(Locale.ROOT));
            return adapters.stream().filter(adapter -> adapter.channel() == requested).findFirst().orElse(null);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
