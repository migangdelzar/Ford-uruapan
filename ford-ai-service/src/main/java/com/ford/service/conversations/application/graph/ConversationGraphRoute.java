package com.ford.service.conversations.application.graph;

public enum ConversationGraphRoute {
    BOT_REPLY("bot_reply"),
    WARM_FOLLOW_UP("warm_follow_up"),
    HUMAN_PENDING("human_pending"),
    IGNORED("ignored");

    private final String terminalNode;

    ConversationGraphRoute(String terminalNode) {
        this.terminalNode = terminalNode;
    }

    public String terminalNode() {
        return terminalNode;
    }
}
