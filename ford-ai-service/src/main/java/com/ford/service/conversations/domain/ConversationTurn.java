package com.ford.service.conversations.domain;

import com.ford.service.channels.domain.ReplyOption;

import java.util.List;

public record ConversationTurn(String text, List<ReplyOption> replyOptions) {
    public ConversationTurn(String text) {
        this(text, List.of());
    }

    public ConversationTurn {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Conversation turn text is required");
        }
        replyOptions = List.copyOf(replyOptions == null ? List.of() : replyOptions);
    }
}
