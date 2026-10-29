package com.ford.service.ai.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated quality-gate outcome with a bounded, content-free reason code. */
public record QualityDecision(Status status, String reasonCode) {
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public QualityDecision {
        Objects.requireNonNull(status, "quality status is required");
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("quality reason code is invalid");
        }
        if ((status == Status.PASS) != "accepted".equals(reasonCode)) {
            throw new IllegalArgumentException("quality status and reason code disagree");
        }
    }

    public static QualityDecision pass() { return new QualityDecision(Status.PASS, "accepted"); }
    public static QualityDecision repair(String reason) { return new QualityDecision(Status.REPAIR, reason); }
    public static QualityDecision clarify(String reason) { return new QualityDecision(Status.CLARIFY, reason); }
    public static QualityDecision handoff(String reason) { return new QualityDecision(Status.HANDOFF, reason); }
    public static QualityDecision block(String reason) { return new QualityDecision(Status.BLOCK, reason); }

    public enum Status { PASS, REPAIR, CLARIFY, HANDOFF, BLOCK }
}
