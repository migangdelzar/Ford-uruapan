package com.ford.service.shared;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SourceMetadata(
        String sourceSystem,
        String sourceRecordId,
        Instant observedAt,
        Instant effectiveFrom,
        Instant effectiveTo,
        FreshnessStatus freshnessStatus,
        String rawPayload,
        UUID syncRunId) {

    public SourceMetadata {
        requireText(sourceSystem, "sourceSystem");
        requireText(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(freshnessStatus, "freshnessStatus must not be null");
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        Objects.requireNonNull(syncRunId, "syncRunId must not be null");
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must not precede effectiveFrom");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
