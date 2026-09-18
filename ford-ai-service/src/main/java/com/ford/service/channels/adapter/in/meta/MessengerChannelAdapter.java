package com.ford.service.channels.adapter.in.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;

public final class MessengerChannelAdapter extends AbstractMetaChannelAdapter {
    public MessengerChannelAdapter(ObjectMapper objectMapper) { super(objectMapper); }
    public MessengerChannelAdapter(ObjectMapper objectMapper, String appSecret) { super(objectMapper, appSecret); }
    @Override public Channel channel() { return Channel.MESSENGER; }
    @Override protected JsonNode messageNode(JsonNode root) { return root.at("/entry/0/messaging/0/message"); }
    @Override protected String messageIdPath() { return "/mid"; }
    @Override protected String participantPath() { return "/../sender/id"; }
    @Override protected String textPath() { return "/text"; }

    @Override
    public com.ford.service.channels.domain.InboundMessage parseInbound(com.ford.service.channels.domain.RawWebhook payload) {
        var root = readTree(payload.body());
        var message = messageNode(root);
        var participantId = root.at("/entry/0/messaging/0/sender/id").asText(null);
        return normalize(payload, message, participantId);
    }
}
