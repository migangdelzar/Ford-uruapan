package com.ford.service.ai.application;

/** Marks a provider rejection of a follow-up completion after a read-only tool returned data. */
public final class ProviderFollowupRejectedException extends RuntimeException {

    public ProviderFollowupRejectedException(Throwable cause) {
        super("provider rejected completion after read-only tool result", cause);
    }
}
