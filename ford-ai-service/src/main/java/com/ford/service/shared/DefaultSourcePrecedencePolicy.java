package com.ford.service.shared;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class DefaultSourcePrecedencePolicy implements SourcePrecedencePolicy {

    @Override
    public SourceSelection choose(List<SourceCandidate> candidates, Instant now) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(now, "now must not be null");

        return candidates.stream()
                .filter(candidate -> isUsable(candidate, now))
                .sorted(Comparator
                        .comparingInt((SourceCandidate candidate) -> candidate.sourceKind().precedence())
                        .thenComparing(candidate -> candidate.metadata().observedAt(), Comparator.reverseOrder()))
                .findFirst()
                .map(candidate -> new SourceSelection(
                        candidate.sourceKind(), candidate.payload(), candidate.metadata()))
                .orElseThrow(() -> new IllegalStateException("No usable source candidate exists"));
    }

    private boolean isUsable(SourceCandidate candidate, Instant now) {
        var metadata = candidate.metadata();
        return metadata.freshnessStatus() != FreshnessStatus.QUARANTINED
                && (metadata.effectiveFrom() == null || !now.isBefore(metadata.effectiveFrom()))
                && (metadata.effectiveTo() == null || now.isBefore(metadata.effectiveTo()));
    }
}
