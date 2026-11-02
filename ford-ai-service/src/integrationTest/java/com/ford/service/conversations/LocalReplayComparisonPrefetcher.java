package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.VehicleNameIndexEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

final class LocalReplayComparisonPrefetcher {
    private final LocalComparisonPairResolver resolver;
    private final Supplier<List<VehicleNameIndexEntry>> currentEntries;
    private final CatalogTools catalogTools;

    LocalReplayComparisonPrefetcher(LocalComparisonPairResolver resolver,
                                    Supplier<List<VehicleNameIndexEntry>> currentEntries,
                                    CatalogTools catalogTools) {
        this.resolver = Objects.requireNonNull(resolver, "resolver is required");
        this.currentEntries = Objects.requireNonNull(currentEntries, "catalog names provider is required");
        this.catalogTools = Objects.requireNonNull(catalogTools, "catalog tools are required");
    }

    Optional<PrefetchedComparison> prefetch(String message) {
        return prefetch(message, "");
    }

    Optional<PrefetchedComparison> prefetch(String message, String priorBuyerMessages) {
        var result = prefetchResult(message, priorBuyerMessages, null);
        return result.status() == Status.READY ? Optional.of(result.comparison()) : Optional.empty();
    }

    PrefetchResult prefetchResult(String message, String priorBuyerMessages) {
        return prefetchResult(message, priorBuyerMessages, null);
    }

    Optional<LocalComparisonPairResolver.Pair> resolveExactBuyerPair(String message) {
        return resolver.resolve(message, currentEntries.get());
    }

    Optional<LocalComparisonPairResolver.Pair> resolvePairFromHistory(String message, String priorBuyerMessages) {
        return resolver.resolveFollowUp(message, priorBuyerMessages, currentEntries.get());
    }

    boolean isComparisonIntent(String message) {
        return resolver.isComparisonIntent(message);
    }

    PrefetchResult prefetchResult(String message, String priorBuyerMessages,
                                  LocalComparisonPairResolver.Pair rememberedBuyerPair) {
        var pair = resolver.resolveFollowUp(message, priorBuyerMessages, currentEntries.get());
        if (pair.isEmpty() && resolver.isBuyerPairFollowUp(message) && rememberedBuyerPair != null) {
            pair = Optional.of(rememberedBuyerPair);
        }
        if (pair.isEmpty()) {
            return new PrefetchResult(resolver.isComparisonIntent(message)
                    ? Status.PAIR_UNRESOLVED : Status.NOT_A_COMPARISON, null);
        }

        var resolvedPair = pair.orElseThrow();
        var comparison = catalogTools.compareTrims(resolvedPair.model(), resolvedPair.year(),
                resolvedPair.trims(), "summary");
        if (comparison == null || !comparison.missingTrims().isEmpty()
                || comparison.trims().size() != 2 || comparison.comparisons().size() != 1) {
            return new PrefetchResult(Status.CATALOG_RESULT_INCOMPLETE, null);
        }
        return new PrefetchResult(Status.READY, new PrefetchedComparison(resolvedPair, comparison,
                comparison.comparisons().size()));
    }

    record PrefetchedComparison(LocalComparisonPairResolver.Pair pair,
                                CatalogTools.TrimComparison comparison,
                                int evidenceCount) { }

    enum Status { NOT_A_COMPARISON, PAIR_UNRESOLVED, CATALOG_RESULT_INCOMPLETE, READY }

    record PrefetchResult(Status status, PrefetchedComparison comparison) {
        PrefetchResult {
            Objects.requireNonNull(status, "status is required");
            if ((status == Status.READY) != (comparison != null)) {
                throw new IllegalArgumentException("READY requires a comparison; other statuses must not carry one");
            }
        }
    }
}
