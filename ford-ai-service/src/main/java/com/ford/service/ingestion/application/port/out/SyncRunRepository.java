package com.ford.service.ingestion.application.port.out;

import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.ingestion.domain.SyncResult;

import java.util.UUID;

public interface SyncRunRepository {
    void start(UUID syncRunId, SyncRequest request);
    void complete(UUID syncRunId, SyncResult result);
    void fail(UUID syncRunId, String message);
}
