package com.ford.service.ingestion.domain;

import java.util.UUID;

public record SyncResult(UUID syncRunId, Status status, int recordsSeen, int recordsApplied,
                         int recordsQuarantined, boolean staleDataMarked, String errorMessage) {
    public enum Status { COMPLETED, FAILED }
}
