package com.ford.service.ingestion.adapter.out.ford;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ingestion.adapter.out.HttpSourceSnapshotReader;
import com.ford.service.ingestion.application.port.out.FordInventorySource;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.shared.SourceKind;
import org.springframework.web.client.RestClient;

public final class FordApiInventoryAdapter implements FordInventorySource {
    private final RestClient client;
    private final String endpoint;
    private final HttpSourceSnapshotReader reader;

    public FordApiInventoryAdapter(RestClient client, String endpoint, ObjectMapper objectMapper) {
        this.client = client;
        this.endpoint = endpoint;
        this.reader = new HttpSourceSnapshotReader(objectMapper);
    }

    @Override
    public SourceSnapshot fetchInventory(SyncRequest request) {
        return reader.read(client, endpoint, request, SourceKind.OFFICIAL_FEED, "ford-api-inventory");
    }
}
