package com.ford.service.conversations.application.graph;

public enum ConversationGraphRoute {
    BOT_REPLY("bot_reply"),
    SAFE_FALLBACK_REVIEW("safe_fallback_review"),
    WARM_FOLLOW_UP("warm_follow_up"),
    HUMAN_PENDING("human_pending"),
    DUPLICATE("duplicate"),
    OUT_OF_SCOPE("out_of_scope"),
    SHADOW_DRAFT("shadow_draft"),
    NEEDS_OPERATOR("needs_operator");

    private final String terminalNode;

    ConversationGraphRoute(String terminalNode) {
        this.terminalNode = terminalNode;
    }

    public String terminalNode() {
        return terminalNode;
    }
}
