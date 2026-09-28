package com.ford.service.conversations.application.graph;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.UUID;

/** Serializable state is restricted to opaque internal IDs and safe route metadata. */
public final class ConversationGraphState extends AgentState {

    static final String TENANT_ID = "tenantId";
    static final String CONVERSATION_ID = "conversationId";
    static final String RECEIPT_ID = "receiptId";
    static final String ROUTE = "route";
    static final String TERMINAL = "terminal";

    public ConversationGraphState(Map<String, Object> data) {
        super(data);
    }

    static ConversationGraphState initial(ConversationTurnReference reference) {
        return new ConversationGraphState(Map.of(
                TENANT_ID, reference.tenantId().toString(),
                CONVERSATION_ID, reference.conversationId().toString(),
                RECEIPT_ID, reference.receiptId().toString()));
    }

    static ConversationGraphState from(Map<String, Object> data) {
        return new ConversationGraphState(data);
    }

    public ConversationTurnReference reference() {
        return new ConversationTurnReference(
                UUID.fromString(this.<String>value(TENANT_ID).orElseThrow()),
                UUID.fromString(this.<String>value(CONVERSATION_ID).orElseThrow()),
                UUID.fromString(this.<String>value(RECEIPT_ID).orElseThrow()));
    }

    public ConversationGraphRoute route() {
        return this.<String>value(ROUTE).map(ConversationGraphRoute::valueOf).orElse(null);
    }

    public boolean terminal() {
        return value(TERMINAL).map(Boolean.class::cast).orElse(false);
    }
}
