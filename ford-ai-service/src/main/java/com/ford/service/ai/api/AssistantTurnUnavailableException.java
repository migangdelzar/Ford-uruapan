package com.ford.service.ai.api;

import java.util.Objects;

/** Content-free signal that a conversation turn must not create an outbound reply. */
public final class AssistantTurnUnavailableException extends RuntimeException {

    private final Disposition disposition;

    public AssistantTurnUnavailableException(Disposition disposition) {
        super("Assistant turn unavailable");
        this.disposition = Objects.requireNonNull(disposition, "disposition is required");
    }

    public Disposition disposition() {
        return disposition;
    }

    public enum Disposition {
        RETRY,
        REVIEW
    }
}
