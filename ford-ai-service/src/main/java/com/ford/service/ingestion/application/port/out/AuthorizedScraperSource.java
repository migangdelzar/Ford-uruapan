package com.ford.service.ingestion.application.port.out;

import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;

public interface AuthorizedScraperSource {
    SourceSnapshot fetchAuthorized(SyncRequest request);
}
