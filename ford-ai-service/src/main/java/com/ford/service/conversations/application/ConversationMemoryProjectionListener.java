package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class ConversationMemoryProjectionListener {

    private final ConversationMemoryPort memory;

    public ConversationMemoryProjectionListener(ConversationMemoryPort memory) {
        this.memory = memory;
    }

    @ApplicationModuleListener
    public void on(ConversationMemoryProjectionRefreshRequested event) {
        memory.updateProjection(event.key(), event.update(), event.expectedVersion());
    }
}
