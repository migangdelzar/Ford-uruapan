package com.ford.service.channels.adapter.out.meta;

import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.OutboundMessage;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.List;
import java.util.Map;

public final class MetaRestClientDeliveryAdapter implements ChannelDeliveryPort {

    private final RestClient restClient;
    private final URI endpoint;

    public MetaRestClientDeliveryAdapter(RestClient restClient, URI endpoint) {
        this.restClient = restClient;
        this.endpoint = endpoint;
    }

    @Override
    public DeliveryResult send(OutboundMessage message) {
        try {
            var request = restClient.post().uri(endpoint);
            if (message.idempotencyKey() != null) {
                request = request.header("Idempotency-Key", message.idempotencyKey());
            }
            var response = request
                    .body(Map.of(
                            "recipient", Map.of("id", message.recipientId()),
                            "message", Map.of("text", message.text())))
                    .retrieve().toEntity(Map.class);
            var body = response.getBody();
            var providerMessageId = body == null ? null : firstValue(body, "message_id", "messageId", "id");
            return new DeliveryResult(true, providerMessageId, null, false);
        } catch (RestClientResponseException exception) {
            var status = exception.getStatusCode().value();
            var permanent = exception.getStatusCode().is4xxClientError() && status != 408 && status != 429;
            return new DeliveryResult(false, null, exception.getStatusText(), permanent);
        }
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
