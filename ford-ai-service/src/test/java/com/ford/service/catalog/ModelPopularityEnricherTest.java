package com.ford.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.adapter.out.ClasspathModelPopularityIndex;
import com.ford.service.catalog.api.ModelPopularityEvidence;
import com.ford.service.catalog.api.DocumentProvenance;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.application.ModelPopularityEnricher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPopularityEnricherTest {
    private final ClasspathModelPopularityIndex index = new ClasspathModelPopularityIndex();
    private final ModelPopularityEnricher enricher = new ModelPopularityEnricher(index);

    @Test
    void trimsShareOnlyReviewedParentModelMetrics() {
        var xlt = enricher.enrich(vehicle("Ranger", "XLT"));
        var xl = enricher.enrich(vehicle("Ranger", "XL"));
        var reviewed = index.findForCatalogModel("Ranger").orElseThrow();

        assertThat(xlt.modelPopularity()).isEqualTo(xl.modelPopularity()).isEqualTo(
                new ModelPopularityEvidence(reviewed.modelFamily(), reviewed.cumulativeUnits(),
                        reviewed.cumulativeSharePercent(), reviewed.periodStart(), reviewed.periodEnd(),
                        reviewed.ytd2026Units(), reviewed.ytd2026Status().name(), reviewed.geography(),
                        reviewed.sourceLabel(), reviewed.sourceUrl()));
        assertThat(xlt.modelPopularity().cumulativeUnits()).isEqualTo(17452);
        assertThat(xlt.trim()).isEqualTo("XLT");
        assertThat(xl.trim()).isEqualTo("XL");
        assertThat(Arrays.stream(ModelPopularityEvidence.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("trim", "trimUnits", "trimRank", "rank");
        assertThat(Arrays.stream(VehicleEvidence.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("trimUnits", "trimRank", "rank", "units");
    }

    @Test
    void unknownModelNeverUsesTrimOrSemanticSimilarity() {
        for (var model : List.of("Unknown", "Ranger Raptor", "Territor")) {
            var vehicle = vehicle(model, "Ranger");
            assertThat(enricher.enrich(vehicle)).isSameAs(vehicle);
            assertThat(vehicle.modelPopularity()).isNull();
        }
    }

    @Test
    void reviewedAliasAndMissingYtdKeepSourceMeaning() {
        var vehicle = enricher.enrich(vehicle("Escape HEV", "Platinum"));
        assertThat(vehicle.modelPopularity().modelFamily()).isEqualTo("Escape");
        assertThat(vehicle.modelPopularity().ytd2026Units()).isNull();
        assertThat(vehicle.modelPopularity().ytd2026Status()).isEqualTo("NOT_REPORTED_OR_NOT_MARKETED");
    }

    @Test
    void copyingPreservesAllOtherEvidenceAndPopularityInEitherOrder() {
        var original = new VehicleEvidence("Ranger", 2027, "XL", BigDecimal.TEN, "MXN",
                "model-url", "price-url", null, List.of(), List.of(), "sheet-url", "comparison-url",
                "variant-id", List.of());
        var enriched = enricher.enrich(original);
        var documents = List.of(new DocumentProvenance("doc", "hash", "page-hash", "Ranger sheet",
                "sheet", "sheet-url", "model-url", null, "Ranger", 2027, "XL"));
        assertThat(enriched).usingRecursiveComparison().ignoringFields("modelPopularity").isEqualTo(original);
        assertThat(enriched.withRetrievedEvidence(documents).modelPopularity()).isEqualTo(enriched.modelPopularity());
        assertThat(enriched.withRetrievedEvidence(documents).retrievedEvidence()).isEqualTo(documents);
        assertThat(enricher.enrich(original.withRetrievedEvidence(documents)))
                .isEqualTo(enriched.withRetrievedEvidence(documents));
        assertThat(enriched.withModelPopularity(null)).isEqualTo(original);
    }

    @Test
    void unknownPopularityDoesNotChangeSerializedShape() {
        var json = new ObjectMapper().valueToTree(vehicle("Unknown", "XL"));
        assertThat(json.has("modelPopularity")).isFalse();
    }

    private static VehicleEvidence vehicle(String model, String trim) {
        return new VehicleEvidence(model, 2027, trim, null, "MXN", null, null, null, List.of());
    }
}
