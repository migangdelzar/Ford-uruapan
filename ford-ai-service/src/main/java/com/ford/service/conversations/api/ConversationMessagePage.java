package com.ford.service.conversations.api;

import java.util.List;

public record ConversationMessagePage(List<ConversationMessageView> items, PageInfoView page) {
    public ConversationMessagePage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
