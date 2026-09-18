package com.ford.service.ingestion.adapter.out.ford;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ingestion.adapter.out.HttpSourceSnapshotReader;
import com.ford.service.ingestion.application.port.out.AuthorizedScraperSource;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.shared.SourceKind;
import org.springframework.web.client.RestClient;

public final class AuthorizedFordScraperAdapter implements AuthorizedScraperSource {
    private final RestClient client;
    private final String endpoint;
    private final HttpSourceSnapshotReader reader;

    public AuthorizedFordScraperAdapter(RestClient client, String endpoint, ObjectMapper objectMapper) {
        this.client = client;
        this.endpoint = endpoint;
        this.reader = new HttpSourceSnapshotReader(objectMapper);
    }

    @Override
    public SourceSnapshot fetchAuthorized(SyncRequest request) {
        return reader.read(client, endpoint, request, SourceKind.AUTHORIZED_SCRAPER, "ford-authorized-scraper");
    }
}
