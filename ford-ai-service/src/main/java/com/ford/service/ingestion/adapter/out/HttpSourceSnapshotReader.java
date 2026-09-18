package com.ford.service.ingestion.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.SourceCandidate;
import com.ford.service.shared.SourceKind;
import com.ford.service.shared.SourceMetadata;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HttpSourceSnapshotReader {

    private final ObjectMapper objectMapper;

    public HttpSourceSnapshotReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SourceSnapshot read(RestClient client, String endpoint, SyncRequest request,
                               SourceKind sourceKind, String sourceSystem) {
        var body = client.get().uri(endpoint).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Source returned an empty response");
        }
        try {
            var root = objectMapper.readTree(body);
            var syncRunId = UUID.randomUUID();
            var candidates = new ArrayList<SourceCandidate>();
            if (root.isArray()) {
                for (var node : root) {
                    candidates.add(candidate(node, sourceKind, sourceSystem, syncRunId, request.requestedAt(), candidates.size()));
                }
            } else {
                candidates.add(candidate(root, sourceKind, sourceSystem, syncRunId, request.requestedAt(), 0));
            }
            return new SourceSnapshot(syncRunId, candidates);
        } catch (Exception exception) {
            throw new IllegalStateException("Source response is not valid JSON", exception);
        }
    }

    private SourceCandidate candidate(JsonNode node, SourceKind sourceKind, String sourceSystem,
                                      UUID syncRunId, Instant observedAt, int index) {
        var id = node.path("id").asText(node.path("code").asText("record-" + index));
        var payload = node.toString();
        return new SourceCandidate(sourceKind, payload, new SourceMetadata(sourceSystem, id,
                observedAt, null, null, FreshnessStatus.FRESH, payload, syncRunId));
    }
}
