package com.ford.service.conversations.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import com.ford.service.catalog.api.VehicleModelNames;

/** Bounded operator-only synthesis; this is never part of the BDC argument packet. */
public record InternalHandoffNote(String reasonCode, String urgency, List<Fact> confirmedFacts,
                                  List<Evidence> verifiedEvidence, List<String> unknowns, String nextAction) {
    private static final Set<String> URGENCIES = Set.of("LOW", "NORMAL", "HIGH");
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?iu)([a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|\\+?\\d[\\d ()-]{8,}\\d|\\d{7,})");
    private static final Set<String> TRIMS = Set.of("King Ranch", "Outer Banks", "Dark Horse", "Badlands",
            "Wildtrak", "Platinum", "Lariat", "Tremor", "XLT", "Big Bend", "Heritage", "Raptor", "ST", "Active");

    public InternalHandoffNote {
        if (reasonCode == null || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("invalid internal handoff reason code");
        }
        if (urgency == null || !URGENCIES.contains(urgency)) {
            throw new IllegalArgumentException("invalid internal handoff urgency");
        }
        confirmedFacts = bounded(confirmedFacts, "confirmed facts");
        verifiedEvidence = bounded(verifiedEvidence, "verified evidence");
        unknowns = List.copyOf(unknowns == null ? List.of() : unknowns);
        if (unknowns.size() > 10 || unknowns.stream().anyMatch(value -> value == null
                || !CODE.matcher(value).matches())) {
            throw new IllegalArgumentException("invalid internal handoff unknowns");
        }
        if (nextAction == null || !Set.of("no_action", "schedule_test_drive", "send_quote",
                "connect_advisor", "follow_up").contains(nextAction)) {
            throw new IllegalArgumentException("invalid internal handoff next action");
        }
    }

    private static <T> List<T> bounded(List<T> values, String name) {
        var copy = List.copyOf(values == null ? List.of() : values);
        if (copy.size() > 10) throw new IllegalArgumentException("too many internal handoff " + name);
        return copy;
    }

    public record Fact(String field, String value, String source) {
        public Fact {
            var candidate = value == null ? "" : value.strip();
            var validField = Set.of("vehicle_model", "model_year", "vehicle_trim").contains(field);
            var validSource = Set.of("lead_journey", "current_message").contains(source);
            var validValue = !candidate.isBlank() && candidate.length() <= 80
                    && !SENSITIVE.matcher(candidate).find() && !candidate.contains("\n") && !candidate.contains("\r");
            if (!validField || !validSource || !validValue
                    || "vehicle_model".equals(field) && VehicleModelNames.all().stream()
                    .noneMatch(model -> model.equalsIgnoreCase(candidate))
                    || "model_year".equals(field) && (!candidate.matches("20\\d{2}")
                    || Integer.parseInt(candidate) > 2100)
                    || "vehicle_trim".equals(field) && TRIMS.stream().noneMatch(trim -> trim.equalsIgnoreCase(candidate))) {
                throw new IllegalArgumentException("invalid internal handoff confirmed fact");
            }
            value = candidate;
        }
    }

    public record Evidence(String source, String outcome, Integer evidenceCount, String freshness) {
        public Evidence {
            Objects.requireNonNull(source, "evidence source is required");
            if (!source.matches("[A-Za-z][A-Za-z0-9_]{0,79}") || !"returned".equals(outcome)
                    || evidenceCount == null || evidenceCount < 1 || evidenceCount > 100_000
                    || !"not_recorded".equals(freshness)) {
                throw new IllegalArgumentException("invalid internal handoff evidence");
            }
        }
    }
}
