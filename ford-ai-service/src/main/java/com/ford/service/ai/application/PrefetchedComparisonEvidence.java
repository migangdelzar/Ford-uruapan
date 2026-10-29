package com.ford.service.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.api.AssistantRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Internal live-retrieval envelope. Never construct from model text, HTTP data, or recorded traces. */
public final class PrefetchedComparisonEvidence {
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final int MAX_PAYLOAD_CHARS = 16_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FordCatalogSourceReferencePolicy SOURCES = new FordCatalogSourceReferencePolicy();

    private final AssistantRequest owner;
    private final String snapshotSha256;
    private final UUID executionId = UUID.randomUUID();
    private final Instant retrievedAt = Instant.now();
    private final String payload;

    private PrefetchedComparisonEvidence(AssistantRequest owner, String snapshotSha256, String payload) {
        this.owner = owner;
        this.snapshotSha256 = snapshotSha256;
        this.payload = payload;
    }

    /** Call only after a fresh authorized compare_trims execution against the selected snapshot. */
    public static PrefetchedComparisonEvidence liveComparison(AssistantRequest owner, String snapshotSha256,
                                                              String model, int year, List<String> trims,
                                                              String comparisonPayload) {
        require(owner != null && owner.tenantId() != null && owner.conversationId() != null,
                "request identity is required");
        require(!"MEMORY_COMPACTION".equals(owner.conversationState()), "compaction cannot carry comparison evidence");
        require(snapshotSha256 != null && snapshotSha256.matches("(?i)[a-f0-9]{64}"),
                "catalog snapshot SHA-256 is required");
        require(model != null && !model.isBlank() && year >= 2000 && year <= 2100,
                "exact model and year are required");
        require(trims != null && trims.size() == 2 && trims.stream().allMatch(t -> t != null && !t.isBlank()),
                "two exact trims are required");
        var expected = Set.copyOf(trims.stream().map(PrefetchedComparisonEvidence::normalize).toList());
        require(expected.size() == 2, "trims must be distinct");
        require(comparisonPayload != null && !comparisonPayload.isBlank()
                && comparisonPayload.length() <= MAX_PAYLOAD_CHARS, "bounded comparison payload is required");
        try {
            var root = JSON.readTree(comparisonPayload);
            require(root != null && root.isObject() && normalize(root.path("model").asText()).equals(normalize(model))
                    && root.path("year").isIntegralNumber() && root.path("year").asInt() == year,
                    "comparison identity mismatch");
            require(root.path("missingTrims").isArray() && root.path("missingTrims").isEmpty(),
                    "comparison is incomplete");
            var variants = root.path("trims");
            require(variants.isArray() && variants.size() == 2, "comparison requires two complete trims");
            var actual = new HashSet<String>();
            boolean priced = true;
            boolean hasPriceClaim = false;
            for (var variant : variants) {
                actual.add(normalize(variant.path("trim").asText()));
                hasPriceClaim |= variant.hasNonNull("price");
                priced &= variant.path("price").isNumber() && variant.path("price").decimalValue().signum() >= 0
                        && "MXN".equals(variant.path("currency").asText()) && sourced(variant, "priceSourceUrl");
            }
            require(actual.equals(expected), "comparison trims mismatch");
            var comparisons = root.path("comparisons");
            require(comparisons.isArray() && comparisons.size() == 1, "one comparison pair is required");
            var pair = comparisons.get(0);
            var left = normalize(pair.path("leftTrim").asText());
            var right = normalize(pair.path("rightTrim").asText());
            require(!left.equals(right) && Set.of(left, right).equals(expected), "comparison pair mismatch");
            var differences = pair.path("differences");
            hasPriceClaim |= pair.hasNonNull("priceDifferenceMxn");
            require(!hasPriceClaim || priced, "all populated price claims require source-backed MXN prices for both trims");
            require(differences.isArray(), "comparison differences are required");
            for (var difference : differences) {
                require(!difference.path("name").asText().isBlank()
                        && validSide(difference.path("left"), left)
                        && validSide(difference.path("right"), right), "difference requires source-backed exact sides");
            }
            if (priced) {
                var delta = variants.get(1).path("price").decimalValue()
                        .subtract(variants.get(0).path("price").decimalValue()).abs();
                require(pair.path("priceDifferenceMxn").isNumber()
                        && pair.path("priceDifferenceMxn").decimalValue().abs().compareTo(delta) == 0,
                        "comparison price difference mismatch");
            }
            require(priced || !differences.isEmpty(), "comparison has no source-backed facts or prices");
            return new PrefetchedComparisonEvidence(owner, snapshotSha256, comparisonPayload);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("comparison payload must be valid catalog JSON");
        }
    }

    public boolean validFor(AssistantRequest request, ToolContext context, Instant now) {
        return request == owner && context != null && Objects.equals(owner.tenantId(), context.tenantId())
                && Objects.equals(owner.conversationId(), context.conversationId())
                && now != null && !now.isBefore(retrievedAt) && !now.isAfter(retrievedAt.plus(MAX_AGE));
    }

    String payload() { return payload; }

    private static boolean validSide(JsonNode side, String trim) {
        return normalize(side.path("trim").asText()).equals(trim) && !side.path("value").asText().isBlank()
                && sourced(side, "sourceUrl");
    }

    private static boolean sourced(JsonNode node, String field) {
        return node.path(field).isTextual() && SOURCES.safeReference(node.path(field).asText()).isPresent();
    }

    private static String normalize(String value) { return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    @Override
    public String toString() {
        return "PrefetchedComparisonEvidence[origin=REPLAY_PREFETCH, executionId=" + executionId
                + ", snapshotSha256=" + snapshotSha256 + "]";
    }
}
