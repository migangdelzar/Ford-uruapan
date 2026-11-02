package com.ford.service.catalog.application;

import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.ModelPopularityEvidence;

import java.util.Objects;

/** Pure enrichment using only the reviewed index's catalog-model aliases. */
@org.springframework.modulith.NamedInterface
public final class ModelPopularityEnricher {
    private final ModelPopularityIndex index;

    public ModelPopularityEnricher(ModelPopularityIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    public ModelPopularityIndex index() { return index; }

    public VehicleEvidence enrich(VehicleEvidence vehicle) {
        return index.findForCatalogModel(vehicle.model())
                .map(popularity -> vehicle.withModelPopularity(new ModelPopularityEvidence(
                        popularity.modelFamily(), popularity.cumulativeUnits(), popularity.cumulativeSharePercent(),
                        popularity.periodStart(), popularity.periodEnd(), popularity.ytd2026Units(),
                        popularity.ytd2026Status().name(), popularity.geography(), popularity.sourceLabel(),
                        popularity.sourceUrl())))
                .orElse(vehicle);
    }
}
