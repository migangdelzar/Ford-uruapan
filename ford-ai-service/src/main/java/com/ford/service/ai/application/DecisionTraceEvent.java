package com.ford.service.ai.application;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Content-free, bounded metadata for one decision-pipeline event. */
public record DecisionTraceEvent(String stage, String reasonCode, String route, String modelRole,
                                 String toolName, String outcome, Integer evidenceCount, Instant occurredAt) {
    private static final Set<String> STAGES = Set.of(
            "security", "classifier", "entity_resolution", "scope", "authorization", "route", "tool",
            "evidence", "answer", "quality", "handoff", "finalization");
    private static final Set<String> OUTCOMES = Set.of(
            "started", "completed", "skipped", "returned", "empty", "failed", "fallback", "requested", "queued",
            "not_applicable", "blocked", "allowed", "ambiguous", "denied", "authorized", "unknown",
            "supported", "conflicted", "clarification", "pass", "repair", "handoff", "finalized", "missing",
            "resolved");
    private static final Set<String> MODEL_ROLES = Set.of("classifier", "answer", "none");
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,79}");

    public DecisionTraceEvent {
        stage = required(stage, "stage").toLowerCase(Locale.ROOT);
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("unknown decision trace stage");
        reasonCode = required(reasonCode, "reasonCode").toLowerCase(Locale.ROOT);
        if (!CODE.matcher(reasonCode).matches()) throw new IllegalArgumentException("invalid decision trace reason code");
        route = optionalLabel(route, "route");
        modelRole = optionalLabel(modelRole, "modelRole");
        if (!modelRole.isEmpty() && !MODEL_ROLES.contains(modelRole.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unknown decision trace model role");
        }
        modelRole = modelRole.toLowerCase(Locale.ROOT);
        toolName = optionalLabel(toolName, "toolName");
        outcome = required(outcome, "outcome").toLowerCase(Locale.ROOT);
        if (!OUTCOMES.contains(outcome)) throw new IllegalArgumentException("unknown decision trace outcome");
        if (evidenceCount != null && (evidenceCount < 0 || evidenceCount > 100_000)) {
            throw new IllegalArgumentException("invalid decision trace evidence count");
        }
        if (occurredAt == null) throw new IllegalArgumentException("decision trace timestamp is required");
    }

    private static String optionalLabel(String value, String name) {
        if (value == null || value.isBlank()) return "";
        var normalized = value.strip();
        if (!LABEL.matcher(normalized).matches()) throw new IllegalArgumentException("invalid decision trace " + name);
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 80 || value.contains("\u0000")) {
            throw new IllegalArgumentException("invalid decision trace " + name);
        }
        return value.strip();
    }
}
