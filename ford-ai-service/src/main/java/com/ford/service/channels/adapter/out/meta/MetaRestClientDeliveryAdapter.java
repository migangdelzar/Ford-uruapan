package com.ford.service.channels.adapter.out.meta;

import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.OutboundMessage;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MetaRestClientDeliveryAdapter implements ChannelDeliveryPort {

    private final RestClient restClient;
    private final URI endpoint;
    private final String accessToken;
    private final Channel channel;

    public MetaRestClientDeliveryAdapter(RestClient restClient, URI endpoint, String accessToken, Channel channel) {
        this.restClient = Objects.requireNonNull(restClient);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.accessToken = Objects.requireNonNull(accessToken);
        this.channel = Objects.requireNonNull(channel);
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
            return new DeliveryResult(false, null, exception.getStatusText(), permanent);
        }
    }

    private Map<String, Object> payload(OutboundMessage message) {
        return switch (channel) {
            case WHATSAPP -> Map.of(
                    "messaging_product", "whatsapp",
                    "to", message.recipientId(),
                    "type", "text",
                    "text", Map.of("body", message.text()));
            case MESSENGER -> Map.of(
                    "recipient", Map.of("id", message.recipientId()),
                    "message", Map.of("text", message.text()));
            case INSTAGRAM -> Map.of(
                    "recipient", Map.of("id", message.recipientId()),
                    "message", Map.of("text", message.text()));
        };
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
