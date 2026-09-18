package com.ford.service.ingestion.application;

import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.ingestion.domain.SyncResult;

public interface SyncCoordinator {
    SyncResult run(SyncRequest request);
}
