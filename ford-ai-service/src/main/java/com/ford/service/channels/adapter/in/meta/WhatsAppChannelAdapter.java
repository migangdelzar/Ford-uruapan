package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;

public class WhatsAppChannelAdapter extends AbstractMetaChannelAdapter {
    public WhatsAppChannelAdapter(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public Channel channel() { return Channel.WHATSAPP; }
    @Override protected JsonNode messageNode(JsonNode root) { return root.at("/entry/0/changes/0/value/messages/0"); }
    @Override protected String messageIdPath() { return "/id"; }
    @Override protected String participantPath() { return "/from"; }
    @Override protected String textPath() { return "/text/body"; }
}
