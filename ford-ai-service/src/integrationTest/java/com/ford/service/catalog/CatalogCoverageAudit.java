package com.ford.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogStatus;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.VariantComparisonEvidence;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleNameIndexEntry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Builds a bounded, provenance-aware audit from the read-only catalog port. */
public final class CatalogCoverageAudit {
    private static final int PROMOTION_PAGE_SIZE = 20;
    private static final int COMPARISON_PAGE_SIZE = 50;
    private static final int MAX_COMPARISON_PAGES_PER_VARIANT = 25;
    private static final int OFFER_LIMIT = 50;
    private static final Duration STALE_AFTER = Duration.ofDays(90);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final CatalogPort catalog;
    private final LocalDate asOf;

    public CatalogCoverageAudit(CatalogPort catalog, LocalDate asOf) {
        this.catalog = Objects.requireNonNull(catalog, "catalog port is required");
        this.asOf = Objects.requireNonNull(asOf, "audit date is required");
    }

    public Report audit(SnapshotMetadata snapshot) {
        Objects.requireNonNull(snapshot, "snapshot metadata is required");
        var names = catalog.nameIndexEntries().stream().distinct()
                .sorted(Comparator.comparing(VehicleNameIndexEntry::model, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(VehicleNameIndexEntry::year)
                        .thenComparing(VehicleNameIndexEntry::trim,
                                Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        var variantsToAudit = names.stream().filter(name -> name.trim() != null).toList();
        var sourceHosts = new TreeSet<String>();
        var variants = new ArrayList<VariantCoverage>();
        long factCount = 0;
        long sourcedFactCount = 0;
        long sourcedPriceCount = 0;
        long comparisonFactCount = 0;
        long staleEvidenceCount = 0;
        boolean comparisonRowsTruncated = false;

        for (var name : names) {
            var vehicle = catalog.find(new VehicleLookup(name.model(), name.year(), name.trim())).orElse(null);
            var facts = vehicle == null ? List.<VehicleFact>of() : vehicle.facts();
            var sourcedFacts = facts.stream().filter(CatalogCoverageAudit::hasSourceEvidence).toList();
            factCount += facts.size();
            sourcedFactCount += sourcedFacts.size();
            sourcedFacts.forEach(fact -> addHost(sourceHosts, fact.sourceUrl()));

            var hasSourcedPrice = vehicle != null && vehicle.price() != null
                    && nonBlank(vehicle.priceSourceUrl()) && vehicle.observedAt() != null;
            if (hasSourcedPrice) {
                sourcedPriceCount++;
                addHost(sourceHosts, vehicle.priceSourceUrl());
            }
            var comparisonAudit = name.trim() == null
                    ? new ComparisonAudit(List.of(), false) : comparisonsFor(name);
            comparisonFactCount += comparisonAudit.results().stream()
                    .filter(CatalogCoverageAudit::hasSourceEvidence).count();
            comparisonRowsTruncated |= comparisonAudit.truncated();
            comparisonAudit.results().forEach(comparison -> addHost(sourceHosts, comparison.sourceUrl()));
            staleEvidenceCount += countStale(facts, vehicle, comparisonAudit.results(), snapshot.refreshedAt());
            variants.add(new VariantCoverage(name.model(), name.year(), name.trim(),
                    name.trim() == null ? "NOT_APPLICABLE" : "UNKNOWN",
                    sourcedFacts.size(), facts.size() - sourcedFacts.size(),
                    hasSourcedPrice ? "SOURCED" : "UNKNOWN",
                    sourcedFacts.isEmpty() ? "UNKNOWN" : "SOURCED",
                    (int) comparisonAudit.results().stream().filter(CatalogCoverageAudit::hasSourceEvidence).count()));
        }

        var promotions = allPromotions();
        promotions.forEach(promotion -> addHost(sourceHosts, promotion.sourceUrl()));
        var offers = catalog.offers(new OfferQuery(null, null, null, null, null, null, null,
                null, null, null, false, asOf, OFFER_LIMIT));
        offers.forEach(offer -> addHost(sourceHosts, offer.sourceUrl()));
        var sourcedOfferCount = offers.stream().filter(offer -> nonBlank(offer.sourceUrl())
                && offer.observedAt() != null).count();
        var activePromotions = promotions.stream().filter(promotion -> activeOn(promotion, asOf)).count();
        var expiredPromotions = promotions.stream().filter(promotion -> expiredOn(promotion, asOf)).count();
        var status = catalog.status();
        var modelCount = names.stream().map(VehicleNameIndexEntry::model)
                .map(model -> model.toLowerCase(java.util.Locale.ROOT)).distinct().count();
        var modelYearCount = names.stream().map(name -> name.model().toLowerCase(java.util.Locale.ROOT)
                + "|" + name.year()).distinct().count();

        return new Report(snapshot.label(), snapshot.manifestSha256(), snapshot.refreshedAt(),
                snapshot.fileRowCounts(), "UNVERIFIED", "NOT_RECORDED", modelCount, modelYearCount,
                variantsToAudit.size(), names.size() - variantsToAudit.size(), factCount, sourcedFactCount,
                variants.stream().filter(variant -> variant.factCoverageStatus().equals("UNKNOWN")).count(),
                sourcedPriceCount, comparisonFactCount, offers.size(), sourcedOfferCount,
                offers.size() == OFFER_LIMIT, comparisonRowsTruncated,
                promotions.size(), activePromotions, expiredPromotions, staleEvidenceCount,
                STALE_AFTER.toDays(),
                (int) variants.stream().filter(variant -> variant.trim() != null
                        && variant.packageIdentityStatus().equals("UNKNOWN")).count(),
                List.copyOf(sourceHosts), variants, status);
    }

    public static Path writeJson(Report report, Path projectRoot) {
        Objects.requireNonNull(report, "coverage report is required");
        var root = Objects.requireNonNull(projectRoot, "project root is required").toAbsolutePath().normalize();
        var outputDirectory = root.resolve("build/reports/catalog-coverage").normalize();
        if (!outputDirectory.startsWith(root.resolve("build"))) {
            throw new IllegalArgumentException("coverage report must be written under build/");
        }
        var safeLabel = report.snapshotLabel().replaceAll("[^A-Za-z0-9._-]", "_");
        var output = outputDirectory.resolve("catalog-coverage-" + safeLabel + ".json");
        try {
            Files.createDirectories(outputDirectory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            return output;
        } catch (IOException failure) {
            throw new IllegalStateException("catalog coverage report could not be written", failure);
        }
    }

    private ComparisonAudit comparisonsFor(VehicleNameIndexEntry name) {
        var results = new ArrayList<VariantComparisonEvidence>();
        for (var page = 0; page < MAX_COMPARISON_PAGES_PER_VARIANT; page++) {
            var offset = page * COMPARISON_PAGE_SIZE;
            var resultPage = catalog.variantComparisons(name.model(), name.year(), name.trim(),
                    offset, COMPARISON_PAGE_SIZE);
            results.addAll(resultPage);
            if (resultPage.size() < COMPARISON_PAGE_SIZE) break;
            if (page == MAX_COMPARISON_PAGES_PER_VARIANT - 1) {
                return new ComparisonAudit(List.copyOf(results), true);
            }
        }
        return new ComparisonAudit(List.copyOf(results), false);
    }

    private List<PromotionEvidence> allPromotions() {
        var results = new ArrayList<PromotionEvidence>();
        int offset = 0;
        while (true) {
            PromotionPage page = catalog.promotions(new PromotionQuery(null, null, asOf, false,
                    PROMOTION_PAGE_SIZE, offset));
            results.addAll(page.results());
            if (!page.hasMore() || page.results().isEmpty()) return List.copyOf(results);
            offset += page.results().size();
        }
    }

    private static long countStale(List<VehicleFact> facts, VehicleEvidence vehicle,
                                   List<VariantComparisonEvidence> comparisons, Instant refreshedAt) {
        var cutoff = refreshedAt.minus(STALE_AFTER);
        long count = facts.stream().filter(fact -> fact.observedAt() != null && fact.observedAt().isBefore(cutoff)).count();
        if (vehicle != null && vehicle.observedAt() != null && vehicle.observedAt().isBefore(cutoff)) count++;
        return count + comparisons.stream().filter(item -> item.observedAt() != null
                && item.observedAt().isBefore(cutoff)).count();
    }

    private static boolean hasSourceEvidence(VehicleFact fact) {
        return nonBlank(fact.sourceUrl()) && fact.observedAt() != null;
    }

    private static boolean hasSourceEvidence(VariantComparisonEvidence comparison) {
        return nonBlank(comparison.sourceUrl()) && comparison.observedAt() != null;
    }

    private static boolean activeOn(PromotionEvidence promotion, LocalDate date) {
        return (promotion.validFrom() == null || !promotion.validFrom().isAfter(date))
                && (promotion.validUntil() == null || !promotion.validUntil().isBefore(date));
    }

    private static boolean expiredOn(PromotionEvidence promotion, LocalDate date) {
        return promotion.validUntil() != null && promotion.validUntil().isBefore(date);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void addHost(Set<String> hosts, String sourceUrl) {
        if (!nonBlank(sourceUrl)) return;
        try {
            var uri = URI.create(sourceUrl);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null) {
                var host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
                if (isPublicHostname(host)) hosts.add(host);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid source URLs are not emitted into the report.
        }
    }

    private static boolean isPublicHostname(String host) {
        return host.contains(".") && !host.endsWith(".local") && !host.endsWith(".internal")
                && !host.endsWith(".test") && !host.endsWith(".invalid") && !host.endsWith(".example")
                && !host.matches("[0-9.]+") && !host.contains(":");
    }

    public record SnapshotMetadata(String label, String manifestSha256, Instant refreshedAt,
                                   Map<String, Integer> fileRowCounts) {
        public SnapshotMetadata {
            if (!nonBlank(label) || !nonBlank(manifestSha256) || refreshedAt == null) {
                throw new IllegalArgumentException("snapshot label, manifest hash and refresh time are required");
            }
            fileRowCounts = Map.copyOf(fileRowCounts == null ? Map.of() : fileRowCounts);
        }
    }

    public record VariantCoverage(String model, int year, String trim, String packageIdentityStatus,
                                  long sourcedFactCount, long unknownFactCount, String priceCoverageStatus,
                                  String factCoverageStatus, int comparisonFactCount) { }

    private record ComparisonAudit(List<VariantComparisonEvidence> results, boolean truncated) { }

    public record Report(String snapshotLabel, String manifestSha256, Instant refreshedAt,
                         Map<String, Integer> snapshotFileRowCounts, String officialSourceScope,
                  String sourceSyncStatus, long modelCount, long modelYearCount, long modelYearTrimCount,
                  long modelYearWithoutTrimCount,
                         long factCount, long sourcedFactCount, long variantsWithoutSourcedFacts,
                         long sourcedPriceCount, long comparisonFactCount, int offerCount, long sourcedOfferCount,
                  boolean offersTruncated, boolean comparisonRowsTruncated,
                  int promotionCount, long activePromotionCount, long expiredPromotionCount,
                  long staleEvidenceCount, long staleAfterDays, int unknownPackageIdentityCount, List<String> sourceHosts,
                         List<VariantCoverage> variants, CatalogStatus catalogStatus) {
        public Report {
            snapshotFileRowCounts = Map.copyOf(snapshotFileRowCounts);
            sourceHosts = List.copyOf(sourceHosts);
            variants = List.copyOf(variants);
        }
    }
}
