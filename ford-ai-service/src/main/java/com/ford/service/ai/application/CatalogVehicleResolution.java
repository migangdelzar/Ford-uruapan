package com.ford.service.ai.application;

import java.util.List;
import java.util.Objects;

/** Catalog-backed resolution; identity names here are not vehicle fact evidence. */
public record CatalogVehicleResolution(Status status, VehicleTrimCandidate exact,
                                       List<VehicleTrimCandidate> choices) {
    public CatalogVehicleResolution {
        Objects.requireNonNull(status, "catalog vehicle resolution status is required");
        choices = List.copyOf(choices == null ? List.of() : choices);
        switch (status) {
            case EXACT, UNIQUE_CANONICAL_MATCH -> {
                if (exact == null || !choices.isEmpty())
                    throw new IllegalArgumentException("exact vehicle resolution requires one canonical identity");
            }
            case AMBIGUOUS -> {
                if (exact != null || choices.size() < 2)
                    throw new IllegalArgumentException("ambiguous vehicle resolution requires multiple choices");
            }
            case MISSING, NOT_REQUIRED -> {
                if (exact != null || !choices.isEmpty())
                    throw new IllegalArgumentException("unresolved vehicle result cannot contain an exact identity");
            }
        }
    }

    public enum Status { EXACT, UNIQUE_CANONICAL_MATCH, AMBIGUOUS, MISSING, NOT_REQUIRED }

    public static CatalogVehicleResolution notRequired() {
        return new CatalogVehicleResolution(Status.NOT_REQUIRED, null, List.of());
    }

    public static CatalogVehicleResolution missing() {
        return new CatalogVehicleResolution(Status.MISSING, null, List.of());
    }
}
