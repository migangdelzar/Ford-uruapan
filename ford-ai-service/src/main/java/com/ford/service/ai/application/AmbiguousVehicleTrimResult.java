package com.ford.service.ai.application;

import java.util.List;

/** Safe canonical identities for a clarification; deliberately contains no selected vehicle facts. */
public record AmbiguousVehicleTrimResult(String model, int year, List<VehicleTrimCandidate> choices) {
    public AmbiguousVehicleTrimResult {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("vehicle model is required");
        if (year < 2000 || year > 2100) throw new IllegalArgumentException("vehicle year is outside supported range");
        var canonicalModel = model.strip();
        model = canonicalModel;
        choices = List.copyOf(choices == null ? List.of() : choices);
        if (choices.size() < 2) throw new IllegalArgumentException("ambiguous vehicle requires multiple choices");
        if (choices.stream().anyMatch(choice -> !choice.model().equalsIgnoreCase(canonicalModel)
                || choice.year() != year)) {
            throw new IllegalArgumentException("vehicle trim choices must share the requested model and year");
        }
    }
}
