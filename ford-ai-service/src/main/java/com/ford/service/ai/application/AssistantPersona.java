package com.ford.service.ai.application;

import java.util.Objects;
import java.util.UUID;

/** Stable, conversation-scoped assistant display identity. */
public class AssistantPersona {

    private static final String ASSISTANT_NAME = "MÍA";

    public String nameFor(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversation id is required");
        return ASSISTANT_NAME;
    }

    public String introductionFor(UUID conversationId) {
        return "Hola, soy *" + nameFor(conversationId) + "*, su asistente virtual de Ford Uruapan. 👋";
    }
}
