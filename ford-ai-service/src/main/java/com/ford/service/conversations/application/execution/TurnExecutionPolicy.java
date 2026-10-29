package com.ford.service.conversations.application.execution;

import java.time.Duration;
import java.util.Objects;

/** Immutable, versioned limits for one logical conversation turn. */
public record TurnExecutionPolicy(String version, Duration deadline, int providerAttemptLimit,
                                  int toolCallLimit, int repairLimit) {
    private static final Duration MAX_DEADLINE = Duration.ofDays(1);
    private static final int MAX_LIMIT = 10_000;

    public TurnExecutionPolicy {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(deadline, "deadline");
        if (version.isBlank() || deadline.isZero() || deadline.isNegative() || deadline.compareTo(MAX_DEADLINE) > 0
                || providerAttemptLimit < 1 || providerAttemptLimit > MAX_LIMIT
                || toolCallLimit < 1 || toolCallLimit > MAX_LIMIT || repairLimit < 0 || repairLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid turn execution policy");
        }
    }

    public static TurnExecutionPolicy approvedInitial() {
        return new TurnExecutionPolicy("phase2-approved-v1", Duration.ofSeconds(45), 4, 6, 1);
    }
}
