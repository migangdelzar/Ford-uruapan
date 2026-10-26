package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

final class LocalReplayPromotionPrefetcher {
    private final LocalBuyerVehicleResolver resolver;
    private final Supplier<List<VehicleNameIndexEntry>> entries;
    private final CatalogTools tools;

    LocalReplayPromotionPrefetcher(LocalBuyerVehicleResolver resolver,
            Supplier<List<VehicleNameIndexEntry>> entries, CatalogTools tools) {
        this.resolver = Objects.requireNonNull(resolver);
        this.entries = Objects.requireNonNull(entries);
        this.tools = Objects.requireNonNull(tools);
    }

    Optional<PrefetchedPromotion> prefetch(String message, String memoryContext) {
        var identity = resolver.resolve(message, memoryContext, entries.get());
        if (identity.isEmpty()) return Optional.empty();
        var ref = identity.orElseThrow();
        var result = tools.listPromotions(ref.model(), ref.year(), null, false, 20, 0, "full", null);
        var matches = result.results().stream().filter(p -> p.model() != null
                && p.model().equalsIgnoreCase(ref.model()) && Objects.equals(p.year(), ref.year())).toList();
        if (matches.isEmpty() || matches.size() != result.results().size()) return Optional.empty();
        return Optional.of(new PrefetchedPromotion(ref, matches, matches.size()));
    }

    record PrefetchedPromotion(LocalBuyerVehicleResolver.VehicleRef identity,
                               List<CatalogTools.PromotionResult> promotions, int evidenceCount) {
        PrefetchedPromotion { promotions = List.copyOf(promotions); }
    }
}
