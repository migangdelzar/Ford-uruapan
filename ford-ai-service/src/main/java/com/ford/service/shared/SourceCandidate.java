package com.ford.service.shared;

import java.time.Instant;
import java.util.Objects;

public record SourceCandidate(SourceKind sourceKind, String payload, SourceMetadata metadata) {

    public SourceCandidate {
        Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public SourceCandidate withEffectiveTo(Instant effectiveTo) {
        return new SourceCandidate(
                sourceKind,
                payload,
                new SourceMetadata(
                        metadata.sourceSystem(),
                        metadata.sourceRecordId(),
                        metadata.observedAt(),
                        metadata.effectiveFrom(),
                        effectiveTo,
                        metadata.freshnessStatus(),
                        metadata.rawPayload(),
                        metadata.syncRunId()));
    }
}
