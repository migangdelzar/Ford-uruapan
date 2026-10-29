package com.ford.service.ingestion.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.SourceCandidate;
import com.ford.service.shared.SourceMetadata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SourceNormalization {

    private static final Duration FRESHNESS_WINDOW = Duration.ofDays(7);
    private final ObjectMapper objectMapper;

    public SourceNormalization() {
        this(new ObjectMapper());
    }

    public SourceNormalization(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizationResult normalize(SourceSnapshot snapshot, Instant now) {
        var accepted = new ArrayList<SourceCandidate>();
        var quarantined = new ArrayList<SourceCandidate>();
        for (var candidate : snapshot.candidates()) {
            if (!validJsonRecord(candidate.payload())) {
                quarantined.add(withStatus(candidate, FreshnessStatus.QUARANTINED));
                continue;
            }
            var status = candidate.metadata().freshnessStatus();
            if (candidate.metadata().observedAt().plus(FRESHNESS_WINDOW).isBefore(now)
                    && status != FreshnessStatus.QUARANTINED) {
                status = FreshnessStatus.STALE;
            }
            accepted.add(withStatus(candidate, status));
        }
        return new NormalizationResult(accepted, quarantined);
    }

    private boolean validJsonRecord(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node != null && node.isObject()
                    && ((node.hasNonNull("id") && !node.get("id").asText().isBlank())
                    || node.hasNonNull("code"));
        } catch (Exception exception) {
            return false;
        }
    }

    private SourceCandidate withStatus(SourceCandidate candidate, FreshnessStatus status) {
        var metadata = candidate.metadata();
        return new SourceCandidate(candidate.sourceKind(), candidate.payload(), new SourceMetadata(
                metadata.sourceSystem(), metadata.sourceRecordId(), metadata.observedAt(),
                metadata.effectiveFrom(), metadata.effectiveTo(), status, metadata.rawPayload(), metadata.syncRunId()));
    }

    public record NormalizationResult(List<SourceCandidate> accepted, List<SourceCandidate> quarantined) {
        public NormalizationResult {
            accepted = List.copyOf(accepted);
            quarantined = List.copyOf(quarantined);
        }
    }
}
