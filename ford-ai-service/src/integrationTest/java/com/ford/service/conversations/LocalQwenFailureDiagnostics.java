package com.ford.service.conversations;

import java.util.Objects;
import java.util.regex.Pattern;

/** Allowlisted failure fields for local synthetic replay artifacts. */
record LocalQwenFailureDiagnostics(LocalQwenTranscriptWriter.FailureCategory safeReason,
                                   String exceptionType, String turnState,
                                   String persistedFailureCategory, String reviewReason) {
    private static final Pattern SAFE_EXCEPTION = Pattern.compile("[A-Za-z][A-Za-z0-9$]{0,79}");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_]{1,64}");

    LocalQwenFailureDiagnostics {
        Objects.requireNonNull(safeReason, "safe failure reason is required");
        if (exceptionType == null || !SAFE_EXCEPTION.matcher(exceptionType).matches()) {
            throw new IllegalArgumentException("exception type must use the safe class-name format");
        }
        validateCode(turnState, "turn state");
        validateCode(persistedFailureCategory, "persisted failure category");
        validateCode(reviewReason, "review reason");
    }

    private static void validateCode(String value, String label) {
        if (value != null && !SAFE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must use the safe identifier format");
        }
    }
}
