package com.ford.service.conversations.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.modulith.NamedInterface;

/** Strict, null-free customer context supported by the specialist handoff boundary. */
@NamedInterface
public record SpecialistHandoffPayload(
        String reason,
        Optional<String> vehicle,
        Optional<String> needs,
        Optional<String> unresolvedQuestion) {

    public SpecialistHandoffPayload {
        reason = required(reason, "reason", 240);
        vehicle = clean(vehicle, "vehicle", 100);
        needs = clean(needs, "needs", 240);
        unresolvedQuestion = clean(unresolvedQuestion, "unresolvedQuestion", 240);
    }

    /** Produces only supported, known arguments; absent values are omitted. */
    public Map<String, String> arguments() {
        var result = new LinkedHashMap<String, String>();
        result.put("reason", reason);
        vehicle.ifPresent(value -> result.put("vehicle", value));
        needs.ifPresent(value -> result.put("needs", value));
        unresolvedQuestion.ifPresent(value -> result.put("unresolvedQuestion", value));
        return Map.copyOf(result);
    }

    private static Optional<String> clean(Optional<String> value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must use Optional.empty() when unknown");
        return value.map(String::strip).filter(text -> !text.isEmpty()).map(text -> required(text, field, maxLength));
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.contains("\u0000")) {
            throw new IllegalArgumentException("Invalid specialist handoff " + field);
        }
        return value.strip();
    }
}
