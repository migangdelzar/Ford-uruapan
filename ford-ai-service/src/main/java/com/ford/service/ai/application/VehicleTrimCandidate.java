package com.ford.service.ai.application;

import java.util.Objects;

/** Canonical catalog identity offered when a vehicle trim needs clarification. */
public record VehicleTrimCandidate(String model, int year, String trim) {
    public VehicleTrimCandidate {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("vehicle model is required");
        if (year < 2000 || year > 2100) throw new IllegalArgumentException("vehicle year is outside supported range");
        if (trim == null || trim.isBlank()) throw new IllegalArgumentException("vehicle trim is required");
        model = model.strip();
        trim = trim.strip();
        Objects.requireNonNull(model);
    }
}
