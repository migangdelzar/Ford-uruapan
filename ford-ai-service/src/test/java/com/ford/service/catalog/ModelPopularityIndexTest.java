package com.ford.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ford.service.catalog.adapter.out.ClasspathModelPopularityIndex;
import com.ford.service.catalog.application.ModelPopularity;
import com.ford.service.catalog.application.ModelPopularityIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static com.ford.service.catalog.application.ModelPopularity.SourceState.NOT_REPORTED_OR_NOT_MARKETED;
import static com.ford.service.catalog.application.ModelPopularity.SourceState.REPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPopularityIndexTest {
    private static final String DATA = "catalog/inegi-ford-model-popularity.json";

    @Test
    void loadsReviewedCumulativeRankingAndAllTwentyExactRows() {
        ModelPopularityIndex index = new ClasspathModelPopularityIndex();
        var models = index.cumulativeRankedModels();

        assertThat(models).hasSize(20);
        assertThat(models).extracting(ModelPopularity::modelFamily).doesNotHaveDuplicates().containsExactly(
                "Territory", "Ranger", "Transit", "F-150", "Lobo", "Maverick", "Explorer", "F-350",
                "Bronco Sport", "Escape", "Edge", "Bronco", "F-450", "Mustang", "F-250 Super Duty",
                "F-550", "Expedition", "E-Transit", "Transit Courier", "Mustang Mach-E");
        assertThat(models).extracting(ModelPopularity::cumulativeUnits).containsExactly(
                41241L, 17452L, 12606L, 12475L, 9884L, 7128L, 6669L, 6456L, 6048L, 4830L,
                2943L, 2909L, 2872L, 2321L, 2223L, 2140L, 2000L, 1789L, 609L, 385L);
        assertThat(models.stream().mapToLong(ModelPopularity::cumulativeUnits).sum()).isEqualTo(144980);
        assertThat(models).extracting(m -> m.cumulativeSharePercent().toPlainString()).containsExactly(
                "28.4", "12.0", "8.7", "8.6", "6.8", "4.9", "4.6", "4.5", "4.2", "3.3",
                "2.0", "2.0", "2.0", "1.6", "1.5", "1.5", "1.4", "1.2", "0.4", "0.3");
        assertThat(models).extracting(ModelPopularity::ytd2025Units).containsExactly(
                9478L, 4800L, 3710L, 4018L, 2424L, 2171L, 2939L, 1974L, 1735L, 1478L,
                14L, 740L, 766L, 639L, 574L, 637L, 635L, 122L, 0L, 103L);
        assertThat(models).extracting(ModelPopularity::ytd2026Units).containsExactly(
                13719L, 4411L, 2295L, 2805L, 2433L, 1798L, 1247L, 1914L, 1259L, null,
                2379L, 766L, 909L, 615L, 537L, 544L, 591L, 455L, null, 119L);
        assertThat(models).extracting(ModelPopularity::origin).containsExactly(
                "China", "Argentina", "U.K.", "U.S.", "U.S.", "Mexico", "U.S.", "U.S.", "Mexico",
                "U.S.", "Canada (per file)", "U.S.", "U.S.", "U.S.", "U.S.", "U.S.", "U.S.",
                "U.S.", "Turkey", "Mexico");
    }

    @Test
    void retainsNationalModelLevelProvenanceAndSeparateComparablePeriods() {
        for (var model : new ClasspathModelPopularityIndex().cumulativeRankedModels()) {
            assertThat(model.periodStart()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(model.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(model.ytd2025Start()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(model.ytd2025End()).isEqualTo(LocalDate.of(2025, 9, 30));
            assertThat(model.ytd2026Start()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(model.ytd2026End()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(model.geography()).isEqualTo("Mexico (national)");
            assertThat(model.sourceLabel()).contains("INEGI", "RAIAVL_8_9.xlsx");
            assertThat(model.sourceUrl()).isEqualTo("https://www.inegi.org.mx/datosprimarios/iavl/");
            assertThat(model.retrievalDate()).isEqualTo(LocalDate.of(2026, 10, 28));
            assertThat(model.cumulativeStatus()).isEqualTo(REPORTED);
        }
    }

    @Test
    void matchesEveryExplicitAliasOnlyToItsOwnFamily() {
        var index = new ClasspathModelPopularityIndex();
        for (var model : index.cumulativeRankedModels()) {
            for (var alias : model.catalogModelAliases()) {
                assertThat(index.findForCatalogModel(alias)).contains(model);
            }
        }
        for (var alias : List.of("F-150", "f150", " F 150 ", "F–150", "F‑150")) {
            assertThat(index.findForCatalogModel(alias)).get().extracting(ModelPopularity::modelFamily)
                    .isEqualTo("F-150");
        }
        assertFamily(index, "Ranger Crew Cab", "Ranger");
        assertFamily(index, "Lobo Regular Cab", "Lobo");
        assertFamily(index, "Lobo Crew Cab", "Lobo");
        assertFamily(index, "Escape HEV", "Escape");
        assertFamily(index, "F-450 Diesel", "F-450");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "Territor", "Territory XLT", "Ford Territory", "Ranger Raptor",
            "Lobo Raptor", "F-150 Lightning", "Bronco Sports", "Transit Custom", "F@150", "F/150"})
    void neverFuzzyMatchesUnreviewedNames(String name) {
        assertThat(new ClasspathModelPopularityIndex().findForCatalogModel(name)).isEmpty();
    }

    @Test
    void nullNameIsUnmapped() {
        assertThat(new ClasspathModelPopularityIndex().findForCatalogModel(null)).isEmpty();
    }

    @Test
    void keepsDashStatesAsMissingRatherThanVerifiedZeroSales() {
        var index = new ClasspathModelPopularityIndex();
        for (var alias : List.of("Escape", "Escape HEV", "Transit Courier")) {
            var model = index.findForCatalogModel(alias).orElseThrow();
            assertThat(model.ytd2026Status()).isEqualTo(NOT_REPORTED_OR_NOT_MARKETED);
            assertThat(model.ytd2026Units()).isNull();
        }
        var courier = index.findForCatalogModel("Transit Courier").orElseThrow();
        // The approved table marks only 2026 as a dash-derived fallback; do not invent a 2025 marker.
        assertThat(courier.ytd2025Status()).isEqualTo(REPORTED);
        assertThat(courier.ytd2025Units()).isZero();
    }

    @Test
    void aliasSetIsLimitedToTheReviewedFamiliesAndExplicitGroupedLabels() {
        var aliases = new ClasspathModelPopularityIndex().cumulativeRankedModels().stream()
                .flatMap(model -> model.catalogModelAliases().stream()).toList();
        assertThat(aliases).containsExactlyInAnyOrder(
                "Territory", "Ranger", "Ranger Crew Cab", "Transit", "F-150", "Lobo", "Lobo Regular Cab",
                "Lobo Crew Cab", "Maverick", "Explorer", "F-350", "Bronco Sport", "Escape", "Escape HEV",
                "Edge", "Bronco", "F-450", "F-450 Diesel", "Mustang", "F-250 Super Duty", "F-550",
                "Expedition", "E-Transit", "Transit Courier", "Mustang Mach-E");
    }

    @Test
    void retainsDuplicateMaverickDiagnosticWithoutDoubleCountingOrCallingTheDashZero() throws IOException {
        var diagnostic = fixture().withArray("sourceDiagnostics").get(0);
        assertThat(diagnostic.path("modelLabel").asText()).isEqualTo("Maverick");
        assertThat(diagnostic.path("diagnostic").asText()).isEqualTo("DUPLICATE_SOURCE_LABEL");
        assertThat(diagnostic.path("includedObservation").asText()).contains("First Maverick row");
        assertThat(diagnostic.path("excludedObservation").asText()).contains("Second Maverick row", "dash");
        assertThat(diagnostic.path("excludedObservationState").asText())
                .isEqualTo("NOT_REPORTED_OR_NOT_MARKETED");
        assertThat(new ClasspathModelPopularityIndex().findForCatalogModel("Maverick").orElseThrow().cumulativeUnits())
                .isEqualTo(7128);
    }

    @Test
    void rankingsAreDeterministicDescendingAndImmutable() {
        var index = new ClasspathModelPopularityIndex();
        assertThat(index.cumulativeRankedModels()).isSortedAccordingTo(
                Comparator.comparingLong(ModelPopularity::cumulativeUnits).reversed()
                        .thenComparing(ModelPopularity::modelFamily));
        assertThat(index.ytd2026RankedModels()).extracting(ModelPopularity::modelFamily).containsExactly(
                "Territory", "Ranger", "F-150", "Lobo", "Edge", "Transit", "F-350", "Maverick",
                "Bronco Sport", "Explorer", "F-450", "Bronco", "Mustang", "Expedition", "F-550",
                "F-250 Super Duty", "E-Transit", "Mustang Mach-E", "Escape", "Transit Courier");
        assertThat(index.ytd2026RankedModels()).isEqualTo(new ClasspathModelPopularityIndex().ytd2026RankedModels());
        assertThatThrownBy(() -> index.cumulativeRankedModels().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> index.cumulativeRankedModels().getFirst().catalogModelAliases().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsWrongCountDuplicateFamiliesBadTotalAndCollidingAliases() throws IOException {
        rejects(root -> root.withArray("models").remove(0));
        rejects(root -> row(root, 1).put("modelFamily", "Territory"));
        rejects(root -> row(root, 0).put("cumulativeUnits", 41242));
        rejects(root -> row(root, 1).withArray("catalogModelAliases").add("f 150"));
        rejects(root -> row(root, 0).withArray("catalogModelAliases").add("territory"));
    }

    @Test
    void rejectsNegativeNumbersMissingMetadataInvalidPeriodsAndImplicitStates() throws IOException {
        rejects(root -> row(root, 0).put("cumulativeUnits", -1));
        rejects(root -> row(root, 0).put("cumulativeSharePercent", -1));
        rejects(root -> row(root, 0).put("ytd2026Units", -1));
        rejects(root -> row(root, 0).remove("ytd2026Status"));
        rejects(root -> row(root, 0).putNull("ytd2026Units"));
        rejects(root -> row(root, 9).put("ytd2026Units", 0));
        rejects(root -> root.put("periodEnd", "2023-12-31"));
        rejects(root -> root.put("ytd2025End", "2024-12-31"));
        rejects(root -> root.put("retrievalDate", "2026-09-01"));
        rejects(root -> root.remove("sourceLabel"));
        rejects(root -> root.put("sourceUrl", ""));
        rejects(root -> row(root, 0).put("ytd2026Units", 1.5));
        rejects(root -> row(root, 0).put("ytd2026Status", "UNKNOWN"));
        rejects(root -> row(root, 0).put("cumulativeSharePercent", 101));
        rejects(root -> row(root, 0).put("cumulativeUnits", Long.MAX_VALUE));
        rejects(root -> row(root, 0).remove("cumulativeUnits"));
        rejects(root -> row(root, 0).put("origin", ""));
        rejects(root -> root.put("sourceUrl", "file:///data.xlsx"));
        rejects(root -> root.put("periodStart", "not a date"));
        rejects(root -> root.put("version", "unreviewed"));
    }

    @Test
    void preservesExplicitStaleAndMissingStatesWithoutInventingUnits() throws IOException {
        for (var state : List.of("STALE", "NOT_AVAILABLE", "NOT_DISCLOSED")) {
            var root = fixture();
            row(root, 0).put("ytd2026Status", state).putNull("ytd2026Units");
            var model = load(root).findForCatalogModel("Territory").orElseThrow();
            assertThat(model.ytd2026Status().name()).isEqualTo(state);
            assertThat(model.ytd2026Units()).isNull();
        }
    }

    @Test
    void failsClosedForMissingOrMalformedResource() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClasspathModelPopularityIndex(
                new ClassPathResource("catalog/not-present.json"), new ObjectMapper()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClasspathModelPopularityIndex(
                new ByteArrayResource("not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)), new ObjectMapper()));
    }

    private static void assertFamily(ModelPopularityIndex index, String alias, String family) {
        assertThat(index.findForCatalogModel(alias)).get().extracting(ModelPopularity::modelFamily).isEqualTo(family);
    }

    private static ObjectNode fixture() throws IOException {
        try (var input = new ClassPathResource(DATA).getInputStream()) {
            return (ObjectNode) new ObjectMapper().readTree(input);
        }
    }

    private static ObjectNode row(ObjectNode root, int index) {
        return (ObjectNode) root.withArray("models").get(index);
    }

    private static ClasspathModelPopularityIndex load(ObjectNode root) throws IOException {
        return new ClasspathModelPopularityIndex(new ByteArrayResource(new ObjectMapper().writeValueAsBytes(root)),
                new ObjectMapper());
    }

    private static void rejects(Consumer<ObjectNode> mutation) throws IOException {
        var root = fixture();
        mutation.accept(root);
        assertThatIllegalArgumentException().isThrownBy(() -> load(root));
    }
}
