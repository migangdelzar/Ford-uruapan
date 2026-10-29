package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested;
import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

@Component
public class ConversationMemoryProjectionListener {

    private final ConversationMemoryPort memory;
    private final AiObservationService observations;

    public ConversationMemoryProjectionListener(ConversationMemoryPort memory) {
        this(memory, new DefaultAiObservationService(ObservationRegistry.NOOP));
    }

    @Autowired
    public ConversationMemoryProjectionListener(ConversationMemoryPort memory,
                                                ObjectProvider<AiObservationService> observationServices) {
        this(memory, observationServices.getIfAvailable(
                () -> new DefaultAiObservationService(ObservationRegistry.NOOP)));
    }

    public ConversationMemoryProjectionListener(ConversationMemoryPort memory, AiObservationService observations) {
        this.memory = memory;
        this.observations = observations;
    }

    @ApplicationModuleListener
    public void on(ConversationMemoryProjectionRefreshRequested event) {
        try {
            var result = memory.updateProjection(event.key(), event.update(), event.expectedVersion());
            observations.event("ford.conversation.memory_projection", java.util.Map.of(
                    "outcome", result.applied() ? "success" : "skipped"));
        } catch (RuntimeException exception) {
            observations.event("ford.conversation.memory_projection", java.util.Map.of(
                    "outcome", "failure", "failure_category", "persistence"));
            throw exception;
        }
    }
}
