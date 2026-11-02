package com.ford.service.ingestion.domain;

import com.ford.service.shared.SourceCandidate;

import java.util.List;
import java.util.UUID;

public record SourceSnapshot(UUID syncRunId, List<SourceCandidate> candidates) {
    public SourceSnapshot {
        if (syncRunId == null || candidates == null) {
            throw new IllegalArgumentException("syncRunId and candidates are required");
        }
        candidates = List.copyOf(candidates);
    }
}
