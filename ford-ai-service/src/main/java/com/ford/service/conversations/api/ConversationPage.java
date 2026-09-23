package com.ford.service.conversations.api;

import java.util.List;

public record ConversationPage(List<ConversationView> items, PageInfoView page) {
    public ConversationPage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
