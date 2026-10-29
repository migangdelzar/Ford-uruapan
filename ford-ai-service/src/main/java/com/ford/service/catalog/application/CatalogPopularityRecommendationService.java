package com.ford.service.catalog.application;

import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.ModelPopularityEvidence;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleSearchQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Applies hard catalog filters before consulting popularity rankings. */
@org.springframework.modulith.NamedInterface
public final class CatalogPopularityRecommendationService {
    private final CatalogPort catalog;
    private final ModelPopularityIndex index;

    public CatalogPopularityRecommendationService(CatalogPort catalog, ModelPopularityIndex index) {
        this.catalog = Objects.requireNonNull(catalog);
        this.index = Objects.requireNonNull(index);
    }

    public Recommendation recommend(VehicleSearchQuery query, boolean currentTrend, int limit) {
        Objects.requireNonNull(query);
        if (limit < 1 || limit > 5) throw new IllegalArgumentException("limit must be between 1 and 5");
        var ranked = (currentTrend ? index.ytd2026RankedModels() : index.cumulativeRankedModels()).stream()
                .filter(popularity -> !currentTrend || popularity.ytd2026Status() == ModelPopularity.SourceState.REPORTED)
                .toList();
        var eligibleByVariant = new java.util.LinkedHashMap<String, VehicleEvidence>();
        for (var popularity : ranked) {
            for (var alias : popularity.catalogModelAliases()) {
                var family = index.findForCatalogModel(alias).orElse(null);
                if (family == null || !matchesModelFilter(alias, query.model())) continue;
                var requestedModel = query.model() == null ? alias : query.model();
                var constrained = new VehicleSearchQuery(null, requestedModel, query.modelYear(), query.minPrice(),
                        query.maxPrice(), query.category(), query.bodyType(), query.fuelType(), 5);
                catalog.search(constrained).stream().filter(vehicle -> matches(vehicle, query, family, index))
                        .forEach(vehicle -> eligibleByVariant.putIfAbsent(variantKey(vehicle), vehicle));
            }
        }
        var ordered = eligibleByVariant.values().stream().sorted((left, right) -> Integer.compare(
                rankOf(ranked, right.model(), index), rankOf(ranked, left.model(), index))).limit(limit).toList();
        ordered = ordered.stream().map(new ModelPopularityEnricher(index)::enrich).toList();
        var spotlightVehicle = ranked.stream().filter(popularity -> (currentTrend
                        ? popularity.ytd2026Status() == ModelPopularity.SourceState.REPORTED
                        : popularity.cumulativeStatus() == ModelPopularity.SourceState.REPORTED))
                .flatMap(popularity -> popularity.catalogModelAliases().stream()
                        .filter(alias -> index.findForCatalogModel(alias).map(f -> matchesModelFilter(alias, query.model())).orElse(false))
                         .flatMap(alias -> eligibleByVariant.values().stream()
                                  .filter(vehicle -> sameFamily(index, vehicle.model(), alias))))
                .filter(Objects::nonNull).findFirst().orElse(null);
        var spotlightPopularity = spotlightVehicle == null ? null : index.findForCatalogModel(spotlightVehicle.model()).orElse(null);
        var spotlight = spotlightPopularity == null ? null : new MostSoldEligibleModel(spotlightVehicle,
                new Sales(currentTrend ? spotlightPopularity.ytd2026Units() : spotlightPopularity.cumulativeUnits(),
                        currentTrend ? spotlightPopularity.ytd2026Start() : spotlightPopularity.periodStart(),
                        currentTrend ? spotlightPopularity.ytd2026End() : spotlightPopularity.periodEnd(),
                        currentTrend ? spotlightPopularity.ytd2026Status().name() : "REPORTED"),
                currentTrend && spotlightPopularity.ytd2025Units() != null
                        ? new Sales(spotlightPopularity.ytd2025Units(), spotlightPopularity.ytd2025Start(),
                        spotlightPopularity.ytd2025End(), spotlightPopularity.ytd2025Status().name()) : null);
        var disclosure = spotlight == null ? null : "Ventas del modelo (no de la versión), "
                + (currentTrend ? "2026-01-01 a 2026-09-30" : "2024-01-01 a 2026-09-30")
                + "; fuente: " + spotlightPopularity.sourceLabel() + " (" + spotlightPopularity.sourceUrl() + ").";
        return new Recommendation(ordered, spotlight, disclosure);
    }

    private static int rankOf(List<ModelPopularity> ranked, String model, ModelPopularityIndex index) {
        var family = index.findForCatalogModel(model).orElse(null);
        if (family == null) return -1;
        for (int i = 0; i < ranked.size(); i++) if (ranked.get(i).modelFamily().equals(family.modelFamily())) return ranked.size() - i;
        return -1;
    }

    private static boolean matches(VehicleEvidence vehicle, VehicleSearchQuery query, ModelPopularity family,
                                   ModelPopularityIndex index) {
        if (!index.findForCatalogModel(vehicle.model()).map(p -> p.modelFamily().equals(family.modelFamily())).orElse(false)) return false;
        if (!matchesModelFilter(vehicle.model(), query.model())) return false;
        if (query.modelYear() != null && !Objects.equals(query.modelYear(), vehicle.year())) return false;
        if (query.minPrice() != null && (vehicle.price() == null || vehicle.price().compareTo(query.minPrice()) < 0)) return false;
        return query.maxPrice() == null || vehicle.price() != null && vehicle.price().compareTo(query.maxPrice()) <= 0;
    }

    private static boolean sameFamily(ModelPopularityIndex index, String first, String second) {
        var firstFamily = index.findForCatalogModel(first).orElse(null);
        var secondFamily = index.findForCatalogModel(second).orElse(null);
        return firstFamily != null && secondFamily != null && firstFamily.modelFamily().equals(secondFamily.modelFamily());
    }

    private static boolean matchesModelFilter(String alias, String model) {
        return model == null || key(alias).equals(key(model));
    }
    private static String key(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(java.util.Locale.ROOT);
    }
    private static String variantKey(VehicleEvidence vehicle) {
        return key(vehicle.model()) + "|" + vehicle.year() + "|" + key(vehicle.trim());
    }

    public record Recommendation(List<VehicleEvidence> results, MostSoldEligibleModel mostSoldEligibleModel,
                                 String periodDisclosure) {
        public Recommendation { results = List.copyOf(results); }
    }
    public record MostSoldEligibleModel(VehicleEvidence eligibleVehicle, Sales sales, Sales comparison) { }
    public record Sales(Long units, LocalDate periodStart, LocalDate periodEnd, String status) { }
}
