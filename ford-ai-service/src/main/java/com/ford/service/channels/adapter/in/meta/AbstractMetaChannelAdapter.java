package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.channels.domain.RawWebhook;
import com.ford.service.channels.domain.WebhookRequest;
import com.ford.service.channels.security.MetaSignatureVerifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        var accountIds = providerAccountIds(body);
        if (accountIds.isEmpty()) {
            throw new IllegalArgumentException("Meta provider account is missing");
        }
        return accountIds.getFirst();
    }

    @Override
    public List<String> providerAccountIds(String body) {
        var ids = new LinkedHashSet<String>();
        var entries = readTree(body).path("entry");
        if (!entries.isArray()) return List.of();
        for (var entry : entries) {
            var accountId = entry.path("id").asText(null);
            var events = entry.path("messaging");
            if (accountId == null || accountId.isBlank() || !events.isArray()) continue;
            for (var event : events) {
                if (isCustomerMessage(event)) {
                    ids.add(accountId);
                    break;
                }
            }
        }
        return List.copyOf(ids);
    }

    @Override
    public List<InboundMessage> parseInboundMessages(RawWebhook payload) {
        var result = new ArrayList<InboundMessage>();
        for (var accountId : providerAccountIds(payload.body())) {
            result.addAll(parseInboundMessages(payload, accountId));
        }
        return List.copyOf(result);
    }

    @Override
    public List<InboundMessage> parseInboundMessages(RawWebhook payload, String providerAccountId) {
        var root = readTree(payload.body());
        var result = new ArrayList<InboundMessage>();
        for (var entry : root.path("entry")) {
            if (!providerAccountId.equals(entry.path("id").asText())) continue;
            for (var event : entry.path("messaging")) {
                if (!isCustomerMessage(event)) continue;
                var message = event.path("message");
                var senderId = event.at("/sender/id").asText(null);
                var messageId = message.path("mid").asText(null);
                if (senderId == null || messageId == null || messageId.isBlank()) continue;
                var text = message.path("text").asText(null);
                if (text == null || text.isBlank()) {
                    text = message.at("/quick_reply/title").asText(null);
                }
                var hasAttachments = message.path("attachments").isArray()
                        && !message.path("attachments").isEmpty();
                var kind = text == null || text.isBlank() || hasAttachments
                        ? InboundMessageKind.UNSUPPORTED : InboundMessageKind.TEXT;
                if (kind == InboundMessageKind.UNSUPPORTED) {
                    text = text == null || text.isBlank()
                            ? "El cliente envió un contenido que este canal todavía no puede procesar."
                            : text + "\nEl cliente también compartió contenido multimedia que no se puede procesar.";
                }
                var timestamp = event.path("timestamp").canConvertToLong()
                        ? Instant.ofEpochMilli(event.path("timestamp").asLong()) : Instant.now();
                result.add(new InboundMessage(payload.tenantId(), channel(), providerAccountId,
                        messageId, senderId, text, List.of(), timestamp, payload.rawPayloadReference(),
                        payload.bindingId(), conversationId(payload.tenantId(), channel(), providerAccountId, senderId), kind));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isCustomerMessage(JsonNode event) {
        var message = event.path("message");
        return message.isObject() && !message.path("is_echo").asBoolean(false)
                && event.at("/sender/id").isTextual() && message.path("mid").isTextual();
    }

    protected abstract JsonNode messageNode(JsonNode root);
    protected abstract String messageIdPath();
    protected abstract String participantPath();
    protected abstract String textPath();

    private static String text(JsonNode node, String pointer) {
        var value = node.at(pointer);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    protected static java.util.UUID conversationId(java.util.UUID tenantId, Channel channel,
                                                   String providerAccountId, String participantId) {
        return java.util.UUID.nameUUIDFromBytes((tenantId + ":" + channel + ":"
                + providerAccountId + ":" + participantId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
