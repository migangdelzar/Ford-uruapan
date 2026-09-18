package com.ford.service.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SourcePrecedencePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-18T18:00:00Z");

    private final SourcePrecedencePolicy policy = new DefaultSourcePrecedencePolicy();

    @Test
    void prefersOfficialFeedOverAuthorizedScraperAndSeed() {
        var selected = policy.choose(List.of(
                candidate(SourceKind.SEED, "seed"),
                candidate(SourceKind.AUTHORIZED_SCRAPER, "scraper"),
                candidate(SourceKind.OFFICIAL_FEED, "official")
        ), NOW);

        assertThat(selected.sourceKind()).isEqualTo(SourceKind.OFFICIAL_FEED);
        assertThat(selected.payload()).isEqualTo("official");
    }

    @Test
    void usesAuthorizedScraperWhenOfficialFeedIsUnavailable() {
        var selected = policy.choose(List.of(
                candidate(SourceKind.SEED, "seed"),
                candidate(SourceKind.AUTHORIZED_SCRAPER, "scraper")
        ), NOW);

        assertThat(selected.sourceKind()).isEqualTo(SourceKind.AUTHORIZED_SCRAPER);
    }

    @Test
    void manualOverrideWinsUntilItExpires() {
        var active = candidate(SourceKind.MANUAL_OVERRIDE, "manual")
                .withEffectiveTo(NOW.plusSeconds(60));

        assertThat(policy.choose(List.of(
                candidate(SourceKind.OFFICIAL_FEED, "official"), active
        ), NOW).sourceKind()).isEqualTo(SourceKind.MANUAL_OVERRIDE);

        assertThat(policy.choose(List.of(
                candidate(SourceKind.OFFICIAL_FEED, "official"), active
        ), NOW.plusSeconds(61)).sourceKind()).isEqualTo(SourceKind.OFFICIAL_FEED);
    }

    @Test
    void fallsBackToSeedWhenNoLiveSourceExists() {
        assertThat(policy.choose(List.of(candidate(SourceKind.SEED, "seed")), NOW).payload())
                .isEqualTo("seed");
    }

    private static SourceCandidate candidate(SourceKind kind, String payload) {
        return new SourceCandidate(
                kind,
                payload,
                new SourceMetadata(
                        "test",
                        UUID.randomUUID().toString(),
                        NOW,
                        NOW.minusSeconds(60),
                        null,
                        FreshnessStatus.FRESH,
                        "{}",
                        UUID.randomUUID()));
    }
}
