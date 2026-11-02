package com.ford.service.channels.adapter.out.meta;

import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.OutboundMessage;
import com.ford.service.channels.domain.ReplyOption;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MetaRestClientDeliveryAdapter implements ChannelDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(MetaRestClientDeliveryAdapter.class);

    private final RestClient restClient;
    private final URI endpoint;
    private final String accessToken;
    private final Channel channel;
    private final ObjectMapper objectMapper;
    private final boolean diagnosticStacktraces;
    private final boolean interactiveMessagesEnabled;

    public MetaRestClientDeliveryAdapter(RestClient restClient, URI endpoint, String accessToken, Channel channel) {
        this(restClient, endpoint, accessToken, channel, new ObjectMapper(), false, false);
    }

    public MetaRestClientDeliveryAdapter(RestClient restClient, URI endpoint, String accessToken, Channel channel,
                                        ObjectMapper objectMapper, boolean diagnosticStacktraces) {
        this(restClient, endpoint, accessToken, channel, objectMapper, diagnosticStacktraces, false);
    }

    public MetaRestClientDeliveryAdapter(RestClient restClient, URI endpoint, String accessToken, Channel channel,
                                        ObjectMapper objectMapper, boolean diagnosticStacktraces,
                                        boolean interactiveMessagesEnabled) {
        this.restClient = Objects.requireNonNull(restClient);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.accessToken = Objects.requireNonNull(accessToken);
        this.channel = Objects.requireNonNull(channel);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.diagnosticStacktraces = diagnosticStacktraces;
        this.interactiveMessagesEnabled = interactiveMessagesEnabled;
    }

    @Override
    public DeliveryResult send(OutboundMessage message) {
        try {
            var request = restClient.post().uri(endpoint);
            if (!accessToken.isBlank()) {
                request = request.header("Authorization", "Bearer " + accessToken);
            }
            if (message.idempotencyKey() != null) {
                request = request.header("Idempotency-Key", message.idempotencyKey());
            }
            var response = request
                    .body(payload(message))
                    .retrieve().toEntity(Map.class);
            var body = response.getBody();
            var providerMessageId = body == null ? null
                    : firstValue(body, "message_id", "messageId", "id", "messages");
            return new DeliveryResult(true, providerMessageId, null, false);
        } catch (RestClientResponseException exception) {
            var status = exception.getStatusCode().value();
            var permanent = exception.getStatusCode().is4xxClientError() && status != 408 && status != 429;
            var diagnostic = safeProviderError(exception.getResponseBodyAsString(), status);
            if (diagnosticStacktraces) {
                log.error("Meta delivery rejected: channel={} endpoint={} diagnostic={}", channel,
                        safeEndpoint(endpoint), diagnostic, new IllegalStateException("Meta API rejected delivery"));
            } else {
                log.warn("Meta delivery rejected: channel={} endpoint={} diagnostic={}", channel,
                        safeEndpoint(endpoint), diagnostic);
            }
            return new DeliveryResult(false, null, diagnostic, permanent);
        }
    }

    private String safeProviderError(String body, int status) {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("HTTP " + status, "");
        try {
            var root = objectMapper.readTree(body);
            var error = root == null ? null : root.path("error");
            for (var field : List.of("type", "code", "error_subcode", "fbtrace_id")) {
                var value = error == null ? null : error.get(field);
                if (value != null && !value.isNull() && value.isValueNode()) {
                    fields.put(field, value.isNumber() ? value.numberValue() : value.asText());
                }
            }
        } catch (Exception ignored) {
            // Provider responses can be non-JSON; status remains useful and safe to report.
        }
        return fields.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String safeEndpoint(URI endpoint) {
        return endpoint.getScheme() + "://" + endpoint.getHost() + endpoint.getPath();
    }

    private Map<String, Object> payload(OutboundMessage message) {
        return switch (channel) {
            case WHATSAPP -> whatsappPayload(message);
            case MESSENGER -> Map.of(
                    "recipient", Map.of("id", message.recipientId()),
                    "message", Map.of("text", message.text()));
            case INSTAGRAM -> Map.of(
                    "recipient", Map.of("id", message.recipientId()),
                    "message", Map.of("text", message.text()));
        };
    }

    private Map<String, Object> whatsappPayload(OutboundMessage message) {
        var options = message.replyOptions();
        if (!interactiveMessagesEnabled || options.isEmpty() || options.size() > 3
                || options.stream().anyMatch(option -> option.title().codePointCount(0, option.title().length()) > 20)) {
            return Map.of("messaging_product", "whatsapp", "to", message.recipientId(),
                    "type", "text", "text", Map.of("body", message.text()));
        }
        var buttons = options.stream().map(this::replyButton).toList();
        return Map.of("messaging_product", "whatsapp", "to", message.recipientId(), "type", "interactive",
                "interactive", Map.of("type", "button", "body", Map.of("text", message.text()),
                        "action", Map.of("buttons", buttons)));
    }

    private Map<String, Object> replyButton(ReplyOption option) {
        return Map.of("type", "reply", "reply", Map.of("id", option.id(), "title", option.title()));
    }

    private static String firstValue(Map<?, ?> body, String... keys) {
        for (var key : keys) {
            var value = body.get(key);
            if (value != null) {
                if (value instanceof List<?> values && !values.isEmpty() && values.getFirst() instanceof Map<?, ?> nested) {
                    var nestedValue = firstValue(nested, "id", "message_id", "messageId");
                    if (nestedValue != null) {
                        return nestedValue;
                    }
                }
                return value.toString();
            }
        }
        return null;
    }
}
