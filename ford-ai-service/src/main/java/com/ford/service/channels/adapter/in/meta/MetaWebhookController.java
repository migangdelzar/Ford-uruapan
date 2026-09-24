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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/webhooks/meta")
public final class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);

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
            log.info("Meta webhook verification succeeded channel={}", channel);
            return ResponseEntity.ok(challenge);
        }
        log.warn("Meta webhook verification rejected channel={}", channel);
        return ResponseEntity.badRequest().build();
    }

    @PostMapping("/{channel}")
    public ResponseEntity<Void> receive(
            @PathVariable String channel,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String body) {
        var started = System.nanoTime();
        try {
            var adapter = adapterFor(channel);
            if (adapter == null) {
                log.warn("Meta webhook rejected channel={} outcome=unknown_channel durationMs={}",
                        channel, elapsedMs(started));
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
            log.warn("Meta webhook rejected channel={} outcome=invalid_signature durationMs={}",
                    channel, elapsedMs(started));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException exception) {
            log.warn("Meta webhook rejected channel={} outcome=invalid_payload durationMs={}",
                    channel, elapsedMs(started));
            return ResponseEntity.badRequest().build();
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
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
