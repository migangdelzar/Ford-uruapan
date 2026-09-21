package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;

public class WhatsAppChannelAdapter extends AbstractMetaChannelAdapter {
    public WhatsAppChannelAdapter(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public Channel channel() { return Channel.WHATSAPP; }
    @Override public String providerAccountId(String body) {
        var root = readTree(body);
        var phoneNumberId = root.at("/entry/0/changes/0/value/metadata/phone_number_id").asText(null);
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            throw new IllegalArgumentException("Meta WhatsApp phone number ID is missing");
        }
        return phoneNumberId;
    }
    @Override protected JsonNode messageNode(JsonNode root) { return root.at("/entry/0/changes/0/value/messages/0"); }
    @Override protected String messageIdPath() { return "/id"; }
    @Override protected String participantPath() { return "/from"; }
    @Override protected String textPath() { return "/text/body"; }
}
