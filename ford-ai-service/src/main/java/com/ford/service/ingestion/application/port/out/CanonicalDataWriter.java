package com.ford.service.ingestion.application.port.out;

import com.ford.service.shared.SourceCandidate;

import java.util.List;
import java.util.UUID;

public interface CanonicalDataWriter {
    int write(UUID tenantId, UUID syncRunId, List<SourceCandidate> candidates);
}
