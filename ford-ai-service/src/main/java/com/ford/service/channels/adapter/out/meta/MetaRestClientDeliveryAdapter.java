package com.ford.service.channels.adapter.out.meta;

import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import com.ford.service.channels.domain.DeliveryResult;
import com.ford.service.channels.domain.OutboundMessage;
import org.springframework.web.client.RestClient;

import java.net.URI;
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
        restClient.post().uri(endpoint).body(Map.of(
                        "recipient", Map.of("id", message.recipientId()),
                        "message", Map.of("text", message.text())))
                .retrieve().toBodilessEntity();
        return new DeliveryResult(true, null, null);
    }
}
