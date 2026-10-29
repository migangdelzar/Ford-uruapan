package com.ford.service.catalog.api;

/** Names-only identity row for building a bounded conversational catalog index. */
public record VehicleNameIndexEntry(String model, int year, String trim) {
    public VehicleNameIndexEntry {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        if (year < 2000 || year > 2100) throw new IllegalArgumentException("year is outside supported range");
        model = model.strip();
        trim = trim == null || trim.isBlank() ? null : trim.strip();
    }
}
