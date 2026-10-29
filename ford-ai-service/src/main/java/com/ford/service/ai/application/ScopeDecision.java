package com.ford.service.ai.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Content-free allow/deny result from the deterministic assistant scope policy. */
public record ScopeDecision(Status status, String reasonCode) {
    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public ScopeDecision {
        Objects.requireNonNull(status, "scope status is required");
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("scope reason code is invalid");
        }
        if ((status == Status.ALLOWED) != "allowed".equals(reasonCode)) {
            throw new IllegalArgumentException("scope status and reason code disagree");
        }
    }

    public enum Status { ALLOWED, DENIED }
}
