package com.ford.service.catalog;

import com.ford.service.catalog.api.CatalogQueryVector;
import com.ford.service.catalog.api.CatalogCandidateLists;
import com.ford.service.catalog.api.CatalogRetrievalCandidate;
import com.ford.service.catalog.api.CatalogSearchPage;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.catalog.application.CatalogRetrievalService;
import com.ford.service.knowledge.application.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSemanticRetrievalTest {

    @Test
    void acceptsOnlyTheStoredBgeM3ModelAndExactly1024FiniteValues() {
        var vector = new CatalogQueryVector("bge-m3", vector(1024));

        assertThat(vector.storedModel()).isEqualTo("bge-m3");
        assertThat(vector.values()).hasSize(1024);
    }

    @Test
    void rejectsAnyQueryVectorModelOtherThanTheStoredModel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQueryVector("nomic-embed-text", vector(1024)));
    }

    @Test
    void rejectsWrongDimensionAndNonFiniteComponents() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQueryVector("bge-m3", vector(1023)));

        var nonFinite = new ArrayList<>(vector(1024));
        nonFinite.set(17, Float.NaN);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogQueryVector("bge-m3", nonFinite));
    }

    @Test
    void doesNotExposeMutableQueryVectorStorage() {
        var input = new ArrayList<>(vector(1024));
        var query = new CatalogQueryVector("bge-m3", input);
        input.set(0, 42.0f);

        assertThat(query.values().getFirst()).isEqualTo(1.0f);
        assertThatThrownBy(() -> query.values().add(3.0f))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsCandidateListsImmutableAndRequiresAValidSemanticState() {
        var candidate = new CatalogRetrievalCandidate<>("vehicle:2027", "Ranger", "Ranger evidence");
        var source = new ArrayList<>(List.of(candidate));
        var lists = new CatalogCandidateLists<>(source, List.of(), List.of(), "unavailable");
        source.clear();

        assertThat(lists.fullText()).containsExactly(candidate);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogCandidateLists<>(List.of(), List.of(), List.of(), "ready-ish"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogRetrievalCandidate<>(" ", "Ranger", "evidence"));
    }

    @Test
    void validatesSearchPageStateAndCopiesResultLists() {
        var source = new ArrayList<>(List.of("Ranger"));
        var page = new CatalogSearchPage<>("ready", 1, source);
        source.clear();

        assertThat(page.results()).containsExactly("Ranger");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CatalogSearchPage<>("pending", 0, List.of()));
    }

    @Test
    void keepsLexicalVehicleResultsWhenTheEmbeddingEndpointIsUnavailable() {
        var query = new VehicleSearchQuery("SUV compacta familiar", null, null,
                null, null, null, null, null, 3);
        var evidence = vehicle("Escape", 2026);
        var candidate = new CatalogRetrievalCandidate<>("escape-2026", "Escape SUV compacta", evidence);
        var port = mock(CatalogPort.class);
        var embedCalls = new AtomicReference<String>();
        when(port.searchCandidates(query, null)).thenReturn(
                new CatalogCandidateLists<>(List.of(candidate), List.of(), List.of(), "unavailable"));
        var service = new CatalogRetrievalService(port, text -> {
            embedCalls.set(text);
            return Optional.empty();
        }, new ReciprocalRankFusion());

        var page = service.searchVehicles(query);

        assertThat(page.semanticSearch()).isEqualTo("unavailable");
        assertThat(page.results()).containsExactly(evidence);
        assertThat(embedCalls.get()).isEqualTo(query.query());
        verify(port).searchCandidates(query, null);
    }

    @Test
    void keepsLexicalVehicleResultsWhenTheEmbeddingCallTimesOut() {
        var query = new VehicleSearchQuery("SUV compacta familiar", null, null,
                null, null, null, null, null, 3);
        var evidence = vehicle("Escape", 2026);
        var candidate = new CatalogRetrievalCandidate<>("escape-2026", "Escape SUV compacta", evidence);
        var port = mock(CatalogPort.class);
        when(port.searchCandidates(query, null)).thenReturn(
                new CatalogCandidateLists<>(List.of(candidate), List.of(), List.of(), "unavailable"));
        var service = new CatalogRetrievalService(port, text -> {
            throw new IllegalStateException("embedding timed out");
        }, new ReciprocalRankFusion());

        var page = service.searchVehicles(query);

        assertThat(page.semanticSearch()).isEqualTo("unavailable");
        assertThat(page.results()).containsExactly(evidence);
        verify(port).searchCandidates(query, null);
    }

    @Test
    void doesNotRequestAnEmbeddingForExactOrBlankSearches() {
        var query = new VehicleSearchQuery(" ", "Ranger", 2027, null, null,
                null, null, null, 3);
        var evidence = vehicle("Ranger", 2027);
        var port = mock(CatalogPort.class);
        when(port.search(query)).thenReturn(List.of(evidence));
        var service = new CatalogRetrievalService(port, text -> {
            throw new AssertionError("embedding should not run for exact lookup");
        }, new ReciprocalRankFusion());

        var page = service.searchVehicles(query);

        assertThat(page.semanticSearch()).isEqualTo("not_requested");
        assertThat(page.results()).containsExactly(evidence);
        verify(port).search(query);
    }

    @Test
    void fusesFullTextTrigramAndVectorCandidatesByStableId() {
        var query = new VehicleSearchQuery("SUV familiar Bronco", null, null,
                null, null, null, null, null, 3);
        var vector = new CatalogQueryVector("bge-m3", vector(1024));
        var shared = new CatalogRetrievalCandidate<>("bronco-2026", "Bronco 2026", vehicle("Bronco", 2026));
        var textOnly = new CatalogRetrievalCandidate<>("escape-2026", "Escape 2026", vehicle("Escape", 2026));
        var trigramOnly = new CatalogRetrievalCandidate<>("edge-2026", "Edge 2026", vehicle("Edge", 2026));
        var vectorOnly = new CatalogRetrievalCandidate<>("explorer-2026", "Explorer 2026", vehicle("Explorer", 2026));
        var port = mock(CatalogPort.class);
        when(port.searchCandidates(query, vector)).thenReturn(new CatalogCandidateLists<>(
                List.of(shared, textOnly), List.of(trigramOnly, shared), List.of(vectorOnly, shared), "ready"));
        var service = new CatalogRetrievalService(port, ignored -> Optional.of(vector), new ReciprocalRankFusion());

        var page = service.searchVehicles(query);

        assertThat(page.semanticSearch()).isEqualTo("ready");
        assertThat(page.results()).extracting(VehicleEvidence::model)
                .containsExactly("Bronco", "Explorer", "Edge");
        assertThat(page.resultCount()).isEqualTo(3);
    }

    @Test
    void embedsFreeTextOffersButPreservesExactModelYearAndOfferFilters() {
        var query = new OfferQuery("planes sin intereses Ranger", "Ranger", 2027, "financing",
                null, null, null, null, null, 12, true, java.time.LocalDate.parse("2026-10-19"), 3);
        var vector = new CatalogQueryVector("bge-m3", vector(1024));
        var evidence = new OfferEvidence("offer-1", "Ranger", 2027, "Plan sin intereses",
                "financing", "Ford Credit", null, null, null, null, null, 12, null, null,
                "MXN", null, null, "active", true, null, "https://ford.test/plan", null,
                java.time.Instant.parse("2026-10-19T10:00:00Z"));
        var candidate = new CatalogRetrievalCandidate<>("offer:offer-1", "Ranger plan sin intereses", evidence);
        var port = mock(CatalogPort.class);
        when(port.offerCandidates(query, vector)).thenReturn(new CatalogCandidateLists<>(
                List.of(candidate), List.of(), List.of(candidate), "ready"));
        var service = new CatalogRetrievalService(port, ignored -> Optional.of(vector), new ReciprocalRankFusion());

        var page = service.searchOffers(query);

        assertThat(page.semanticSearch()).isEqualTo("ready");
        assertThat(page.results()).containsExactly(evidence);
        verify(port).offerCandidates(query, vector);
    }

    @Test
    void deduplicatesMultipleRankedDocumentsForTheSameVehicleBeforeReturningThePage() {
        var query = new VehicleSearchQuery("Bronco familia", null, null,
                null, null, null, null, null, 5);
        var vector = new CatalogQueryVector("bge-m3", vector(1024));
        var bronco = vehicle("Bronco", 2026);
        var firstDocument = new CatalogRetrievalCandidate<>("bronco-doc-1", "Bronco familia", bronco);
        var secondDocument = new CatalogRetrievalCandidate<>("bronco-doc-2", "Bronco pasajeros", bronco);
        var port = mock(CatalogPort.class);
        when(port.searchCandidates(query, vector)).thenReturn(new CatalogCandidateLists<>(
                List.of(firstDocument, secondDocument), List.of(), List.of(secondDocument), "ready"));
        var service = new CatalogRetrievalService(port, ignored -> Optional.of(vector), new ReciprocalRankFusion());

        var page = service.searchVehicles(query);

        assertThat(page.results()).containsExactly(bronco);
        assertThat(page.resultCount()).isEqualTo(1);
    }

    private static VehicleEvidence vehicle(String model, int year) {
        return new VehicleEvidence(model, year, null, BigDecimal.valueOf(800_000), "MXN",
                null, null, null, List.of());
    }

    private static List<Float> vector(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> index == 0 ? 1.0f : 0.0f)
                .toList();
    }
}
