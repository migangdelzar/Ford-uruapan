package com.ford.service.ingestion;

import com.ford.service.ingestion.domain.SourceNormalization;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.SourceCandidate;
import com.ford.service.shared.SourceKind;
import com.ford.service.shared.SourceMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourceNormalizationTest {

    @Test
    void acceptsValidRecordAndQuarantinesMalformedPayload() {
        var syncRunId = UUID.randomUUID();
        var observedAt = Instant.parse("2026-09-18T15:00:00Z");
        var valid = new SourceCandidate(SourceKind.OFFICIAL_FEED, "{\"id\":\"mustang-2026\"}",
                new SourceMetadata("ford-api", "mustang-2026", observedAt, null, null,
                        FreshnessStatus.FRESH, "{\"id\":\"mustang-2026\"}", syncRunId));
        var malformed = new SourceCandidate(SourceKind.AUTHORIZED_SCRAPER, "not-json",
                new SourceMetadata("ford-scraper", "bad-1", observedAt, null, null,
                        FreshnessStatus.UNKNOWN, "not-json", syncRunId));

        var result = new SourceNormalization().normalize(
                new SourceSnapshot(UUID.randomUUID(), List.of(valid, malformed)), observedAt);

        assertThat(result.accepted()).singleElement().extracting(SourceCandidate::sourceKind)
                .isEqualTo(SourceKind.OFFICIAL_FEED);
        assertThat(result.quarantined()).singleElement().extracting(SourceCandidate::sourceKind)
                .isEqualTo(SourceKind.AUTHORIZED_SCRAPER);
    }

    @Test
    void marksOlderRecordsStaleWithoutDiscardingThem() {
        var observedAt = Instant.parse("2026-09-01T15:00:00Z");
        var candidate = new SourceCandidate(SourceKind.OFFICIAL_FEED, "{\"id\":\"ranger-2026\"}",
                new SourceMetadata("ford-api", "ranger-2026", observedAt, null, null,
                        FreshnessStatus.FRESH, "{\"id\":\"ranger-2026\"}", UUID.randomUUID()));

        var result = new SourceNormalization().normalize(
                new SourceSnapshot(UUID.randomUUID(), List.of(candidate)),
                Instant.parse("2026-09-18T15:00:00Z"));

        assertThat(result.accepted()).singleElement().extracting(c -> c.metadata().freshnessStatus())
                .isEqualTo(FreshnessStatus.STALE);
    }
}
