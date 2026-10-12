package com.ford.service.ai.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Stable, conversation-scoped assistant display identity. */
public class AssistantPersona {

    public static final List<String> APPROVED_NAMES =
            List.of("Ana", "Daniela", "Fernanda", "Lucía", "Mariana", "Sofía", "Valeria", "Ximena");

    public String nameFor(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversation id is required");
        var seed = conversationId.getMostSignificantBits() ^ conversationId.getLeastSignificantBits();
        var index = (int) Long.remainderUnsigned(seed, APPROVED_NAMES.size());
        return APPROVED_NAMES.get(index);
    }

    public String introductionFor(UUID conversationId) {
        return "Hola, soy *" + nameFor(conversationId) + "*, su asistente de Ford Uruapan. 👋";
    }
}
