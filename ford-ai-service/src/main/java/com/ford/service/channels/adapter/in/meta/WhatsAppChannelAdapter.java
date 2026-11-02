package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundAttachment;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.domain.InboundMessageKind;
import com.ford.service.channels.domain.RawWebhook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

public class WhatsAppChannelAdapter extends AbstractMetaChannelAdapter {
    public WhatsAppChannelAdapter(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public Channel channel() { return Channel.WHATSAPP; }
    @Override public String providerAccountId(String body) {
        for (var entry : readTree(body).path("entry")) {
            for (var change : entry.path("changes")) {
                var id = change.path("value").at("/metadata/phone_number_id").asText(null);
                if (id != null && !id.isBlank()) return id;
            }
        }
        throw new IllegalArgumentException("Meta WhatsApp phone number ID is missing");
    }

    @Override public List<String> providerAccountIds(String body) {
        var accounts = new LinkedHashSet<String>();
        var root = readTree(body);
        for (var entry : root.path("entry")) {
            for (var change : entry.path("changes")) {
                var value = change.path("value");
                if (!value.path("messages").isArray() || value.path("messages").isEmpty()) continue;
                var id = value.at("/metadata/phone_number_id").asText(null);
                if (id != null && !id.isBlank()) accounts.add(id);
            }
        }
        return List.copyOf(accounts);
    }

    @Override public boolean isStatusCallback(String body) {
        var value = readTree(body).at("/entry/0/changes/0/value");
        return value.path("statuses").isArray()
                && value.path("statuses").size() > 0
                && !value.path("messages").isArray();
    }

    @Override protected JsonNode messageNode(JsonNode root) { return root.at("/entry/0/changes/0/value/messages/0"); }
    @Override protected String messageIdPath() { return "/id"; }
    @Override protected String participantPath() { return "/from"; }
    @Override protected String textPath() { return "/text/body"; }

    @Override
    public InboundMessage parseInbound(RawWebhook payload) {
        var messages = parseInboundMessages(payload);
        if (messages.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one WhatsApp message; use parseInboundMessages for batches");
        }
        return messages.get(0);
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
    public List<InboundMessage> parseInboundMessages(RawWebhook payload, String accountId) {
        var root = readTree(payload.body());
        var messages = new ArrayList<InboundMessage>();
        for (var entry : root.path("entry")) {
            for (var change : entry.path("changes")) {
                var value = change.path("value");
                var valueAccountId = value.at("/metadata/phone_number_id").asText(null);
                if (value.path("messages").isArray() && !value.path("messages").isEmpty()) {
                    if (!accountId.equals(valueAccountId)) continue;
                    for (var message : value.path("messages")) {
                        messages.add(normalize(payload, accountId, message));
                    }
                }
            }
        }
        return List.copyOf(messages);
    }

    private InboundMessage normalize(RawWebhook payload, String accountId, JsonNode message) {
        var providerMessageId = message.path("id").asText(null);
        var participantId = message.path("from").asText(null);
        if (providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("WhatsApp provider message ID is required");
        }
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("WhatsApp participant ID is required");
        }

        var type = message.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
        if (type.isBlank() && message.has("text")) {
            type = "text";
        }
        var kind = kind(type);
        var contentNode = message.path(type);
        var text = contentText(kind, contentNode, message.path("interactive"));
        if (kind == InboundMessageKind.TEXT && (text == null || text.isBlank())) {
            throw new IllegalArgumentException("WhatsApp text body is required");
        }
        var attachments = attachment(kind, contentNode);
        var timestamp = receivedAt(message.path("timestamp").asText(null));
        return new InboundMessage(payload.tenantId(), channel(), accountId, providerMessageId,
                participantId, text, attachments, timestamp, payload.rawPayloadReference(),
                payload.bindingId(), conversationId(payload.tenantId(), channel(), accountId, participantId), kind);
    }

    private static InboundMessageKind kind(String type) {
        return switch (type) {
            case "text" -> InboundMessageKind.TEXT;
            case "image" -> InboundMessageKind.IMAGE;
            case "audio" -> InboundMessageKind.AUDIO;
            case "video" -> InboundMessageKind.VIDEO;
            case "document" -> InboundMessageKind.DOCUMENT;
            case "location" -> InboundMessageKind.LOCATION;
            case "interactive" -> InboundMessageKind.INTERACTIVE;
            case "sticker" -> InboundMessageKind.STICKER;
            default -> InboundMessageKind.UNSUPPORTED;
        };
    }

    private static String contentText(InboundMessageKind kind, JsonNode content, JsonNode interactive) {
        return switch (kind) {
            case TEXT -> content.path("body").asText(null);
            case IMAGE, VIDEO, DOCUMENT -> content.path("caption").asText("");
            case LOCATION -> "Ubicación compartida: " + content.path("latitude").asText("")
                    + ", " + content.path("longitude").asText("");
            case INTERACTIVE -> interactiveText(interactive);
            case AUDIO, STICKER, UNSUPPORTED -> "";
        };
    }

    private static String interactiveText(JsonNode interactive) {
        var reply = interactive.path("button_reply");
        if (reply.isMissingNode()) {
            reply = interactive.path("list_reply");
        }
        var title = reply.path("title").asText("");
        var id = reply.path("id").asText("");
        if (title.isBlank() && id.isBlank()) {
            return "";
        }
        return "Opción elegida: " + title + (id.isBlank() ? "" : " (" + id + ")");
    }

    private static List<InboundAttachment> attachment(InboundMessageKind kind, JsonNode content) {
        if (kind != InboundMessageKind.IMAGE && kind != InboundMessageKind.AUDIO
                && kind != InboundMessageKind.VIDEO && kind != InboundMessageKind.DOCUMENT
                && kind != InboundMessageKind.STICKER) {
            return List.of();
        }
        var mediaId = content.path("id").asText(null);
        if (mediaId == null || mediaId.isBlank()) {
            throw new IllegalArgumentException("WhatsApp media ID is required");
        }
        var mimeType = content.path("mime_type").asText(null);
        var filename = content.path("filename").asText(null);
        Long sizeBytes = content.path("file_size").canConvertToLong()
                ? content.path("file_size").asLong() : null;
        return List.of(new InboundAttachment(mediaId, mimeType, filename, sizeBytes));
    }

    private static Instant receivedAt(String epochSeconds) {
        if (epochSeconds == null || epochSeconds.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (RuntimeException ignored) {
            return Instant.now();
        }
    }
}
