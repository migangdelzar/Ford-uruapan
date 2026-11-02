package com.ford.service.ai.application;

/** Indicates a provider response that could not be converted into a usable assistant reply. */
public final class InvalidAssistantResponseException extends RuntimeException {

    private final String reason;

    public InvalidAssistantResponseException(String reason) {
        super("Provider response failed assistant quality validation");
        this.reason = reason == null || reason.isBlank() ? "invalid_response" : reason;
    }

    public String reason() {
        return reason;
    }
}
