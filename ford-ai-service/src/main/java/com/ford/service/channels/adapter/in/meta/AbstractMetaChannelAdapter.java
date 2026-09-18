package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import com.ford.service.channels.security.MetaSignatureVerifier;

import java.time.Instant;
import java.util.List;

abstract class AbstractMetaChannelAdapter implements ChannelAdapter {

    protected final ObjectMapper objectMapper;
    private final MetaSignatureVerifier signatureVerifier = new MetaSignatureVerifier();
    private final String appSecret;

    AbstractMetaChannelAdapter(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    AbstractMetaChannelAdapter(ObjectMapper objectMapper, String appSecret) {
        this.objectMapper = objectMapper;
        this.appSecret = appSecret;
    }

    @Override
    public InboundMessage parseInbound(RawWebhook payload) {
        try {
            var root = objectMapper.readTree(payload.body());
            var message = messageNode(root);
            var providerMessageId = text(message, messageIdPath());
            var participantId = text(message, participantPath());
            var text = text(message, textPath());
            if (providerMessageId == null || participantId == null || text == null || text.isBlank()) {
                throw new IllegalArgumentException("Unsupported or non-text Meta message");
            }
            return new InboundMessage(
                    payload.tenantId(), channel(), providerMessageId, participantId, text,
                    List.of(), Instant.now(), payload.rawPayloadReference());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Meta webhook JSON", exception);
        }
    }

    protected InboundMessage normalize(RawWebhook payload, JsonNode message, String participantId) {
        var providerMessageId = text(message, messageIdPath());
        var text = text(message, textPath());
        if (providerMessageId == null || participantId == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Unsupported or non-text Meta message");
        }
        return new InboundMessage(
                payload.tenantId(), channel(), providerMessageId, participantId, text,
                List.of(), Instant.now(), payload.rawPayloadReference());
    }

    protected JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Meta webhook JSON", exception);
        }
    }

    @Override
    public void validateWebhook(WebhookRequest request) {
        if (appSecret == null || appSecret.isBlank()) {
            throw new SecurityException("Meta app secret is not configured");
        }
        var signature = request.headers().getOrDefault("X-Hub-Signature-256", request.headers().get("x-hub-signature-256"));
        signatureVerifier.verify(request.body(), signature, appSecret);
    }

    protected abstract JsonNode messageNode(JsonNode root);
    protected abstract String messageIdPath();
    protected abstract String participantPath();
    protected abstract String textPath();

    private static String text(JsonNode node, String pointer) {
        var value = node.at(pointer);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
