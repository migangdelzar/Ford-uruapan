package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import com.ford.service.channels.security.MetaSignatureVerifier;

import java.time.Instant;
import java.util.List;

abstract class AbstractMetaChannelAdapter implements ChannelAdapter {

    protected final ObjectMapper objectMapper;
    private final MetaSignatureVerifier signatureVerifier = new MetaSignatureVerifier();

    AbstractMetaChannelAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public InboundMessage parseInbound(RawWebhook payload) {
        try {
            var root = objectMapper.readTree(payload.body());
            var message = messageNode(root);
            var providerMessageId = text(message, messageIdPath());
            var participantId = text(message, participantPath());
            var text = text(message, textPath());
            var providerAccountId = providerAccountId(payload.body());
            if (providerMessageId == null || participantId == null || text == null || text.isBlank()) {
                throw new IllegalArgumentException("Unsupported or non-text Meta message");
            }
            return new InboundMessage(
                    payload.tenantId(), channel(), providerAccountId, providerMessageId, participantId, text,
                    List.of(), Instant.now(), payload.rawPayloadReference(), payload.bindingId(),
                    conversationId(payload.tenantId(), channel(), providerAccountId, participantId));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Meta webhook JSON", exception);
        }
    }

    protected InboundMessage normalize(RawWebhook payload, JsonNode message, String participantId) {
        var providerMessageId = text(message, messageIdPath());
        var text = text(message, textPath());
        var providerAccountId = providerAccountId(payload.body());
        if (providerMessageId == null || participantId == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Unsupported or non-text Meta message");
        }
        return new InboundMessage(
                payload.tenantId(), channel(), providerAccountId, providerMessageId, participantId, text,
                List.of(), Instant.now(), payload.rawPayloadReference(), payload.bindingId(),
                conversationId(payload.tenantId(), channel(), providerAccountId, participantId));
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
        throw new SecurityException("Meta binding secret is required");
    }

    @Override
    public void validateWebhook(WebhookRequest request, String secretReference) {
        var signature = request.headers().getOrDefault("X-Hub-Signature-256", request.headers().get("x-hub-signature-256"));
        signatureVerifier.verify(request.body(), signature, secretReference);
    }

    @Override
    public String providerAccountId(String body) {
        var root = readTree(body);
        var accountId = root.at("/entry/0/id").asText(null);
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Meta provider account is missing");
        }
        return accountId;
    }

    protected abstract JsonNode messageNode(JsonNode root);
    protected abstract String messageIdPath();
    protected abstract String participantPath();
    protected abstract String textPath();

    private static String text(JsonNode node, String pointer) {
        var value = node.at(pointer);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static java.util.UUID conversationId(java.util.UUID tenantId, Channel channel,
                                                String providerAccountId, String participantId) {
        return java.util.UUID.nameUUIDFromBytes((tenantId + ":" + channel + ":"
                + providerAccountId + ":" + participantId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
