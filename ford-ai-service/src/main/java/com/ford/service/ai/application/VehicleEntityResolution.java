package com.ford.service.ai.application;

import java.util.List;
import java.util.Optional;

/** Safe, provenance-tagged vehicle entities extracted from the current turn and trusted memory. */
public record VehicleEntityResolution(
        Optional<String> model,
        Optional<Integer> modelYear,
        Optional<String> trim,
        List<Integer> comparisonYears,
        Source modelSource,
        Source modelYearSource,
        Source trimSource,
        Status status) {

    public static VehicleEntityResolution missing() {
        return new VehicleEntityResolution(Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                Source.NONE, Source.NONE, Source.NONE, Status.MISSING);
    }

    public VehicleEntityResolution {
        model = model == null ? Optional.empty() : model;
        modelYear = modelYear == null ? Optional.empty() : modelYear;
        trim = trim == null ? Optional.empty() : trim;
        comparisonYears = List.copyOf(comparisonYears == null ? List.of() : comparisonYears);
        modelSource = modelSource == null ? Source.NONE : modelSource;
        modelYearSource = modelYearSource == null ? Source.NONE : modelYearSource;
        trimSource = trimSource == null ? Source.NONE : trimSource;
        if (status == null) throw new IllegalArgumentException("vehicle entity status is required");
    }

    public enum Source { CURRENT_MESSAGE, MEMORY_REFERENCE, NONE }
    public enum Status { RESOLVED, MISSING, AMBIGUOUS }
}
