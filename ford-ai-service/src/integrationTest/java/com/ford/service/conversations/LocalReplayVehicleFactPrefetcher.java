package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleNameIndexEntry;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

final class LocalReplayVehicleFactPrefetcher {
    private final LocalBuyerVehicleResolver resolver;
    private final Supplier<List<VehicleNameIndexEntry>> currentEntries;
    private final CatalogTools catalogTools;

    LocalReplayVehicleFactPrefetcher(LocalBuyerVehicleResolver resolver,
                                     Supplier<List<VehicleNameIndexEntry>> currentEntries,
                                     CatalogTools catalogTools) {
        this.resolver = Objects.requireNonNull(resolver);
        this.currentEntries = Objects.requireNonNull(currentEntries);
        this.catalogTools = Objects.requireNonNull(catalogTools);
    }

    Optional<PrefetchedVehicleFact> prefetch(String currentMessage, String memoryContext) {
        var identity = resolver.resolve(currentMessage, memoryContext, currentEntries.get());
        if (identity.isEmpty()) return Optional.empty();
        var vehicle = catalogTools.getVehicle(identity.get().model(), identity.get().year(), identity.get().trim());
        if (!(vehicle instanceof VehicleEvidence evidence) || !matches(identity.get(), evidence) || !hasEvidence(evidence)) {
            return Optional.empty();
        }
        return Optional.of(new PrefetchedVehicleFact(identity.get(), evidence,
                evidence.facts().size() + evidence.retrievedEvidence().size()
                        + (blank(evidence.modelPageUrl()) ? 0 : 1)
                        + (blank(evidence.priceSourceUrl()) ? 0 : 1)
                        + (blank(evidence.technicalSheetUrl()) ? 0 : 1)
                        + (blank(evidence.comparisonUrl()) ? 0 : 1)));
    }

    private static boolean matches(LocalBuyerVehicleResolver.VehicleRef identity, VehicleEvidence evidence) {
        return evidence.year() != null && evidence.year() == identity.year()
                && identity.model().equalsIgnoreCase(evidence.model())
                && identity.trim().equals(normalize(evidence.trim()));
    }

    private static boolean hasEvidence(VehicleEvidence evidence) {
        return !evidence.facts().isEmpty() || !evidence.retrievedEvidence().isEmpty()
                || !blank(evidence.modelPageUrl()) || !blank(evidence.priceSourceUrl())
                || !blank(evidence.technicalSheetUrl()) || !blank(evidence.comparisonUrl());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    record PrefetchedVehicleFact(LocalBuyerVehicleResolver.VehicleRef identity,
                                 VehicleEvidence evidence, int evidenceCount) { }
}
