package com.ford.service.conversations.application;

import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.domain.ConversationState;

import java.util.Objects;
import java.util.UUID;

public final class ConversationService {

    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public boolean acceptMessage(UUID conversationId, String providerMessageId) {
        return repository.recordMessageIfNew(conversationId, providerMessageId);
    }

    public ConversationState state(UUID conversationId) {
        return repository.state(conversationId);
    }

    public void transition(UUID conversationId, ConversationState target) {
        var current = repository.state(conversationId);
        repository.transition(conversationId, current, current.transitionTo(target));
    }
}
