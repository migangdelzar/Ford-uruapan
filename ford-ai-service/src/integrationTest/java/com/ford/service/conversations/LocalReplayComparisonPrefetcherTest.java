package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalReplayComparisonPrefetcherTest {
    private static final List<VehicleNameIndexEntry> ENTRIES = List.of(
            new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
            new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

    @Test
    void callsExactTrimComparisonForResolvedPair() {
        var catalogTools = mock(CatalogTools.class);
        var expected = comparison(List.of(), List.of(pairResult()));
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary")).thenReturn(expected);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        var result = prefetcher.prefetch(
                "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().comparison()).isSameAs(expected);
        assertThat(result.orElseThrow().evidenceCount()).isPositive();
        verify(catalogTools).compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary");
    }

    @Test
    void doesNotCallCatalogToolWhenPairCannotBeResolved() {
        var catalogTools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        assertThat(prefetcher.prefetch("¿Qué precio tiene la Ranger?")).isEmpty();
        verifyNoInteractions(catalogTools);
    }

    @Test
    void reusesNearestExactBuyerPairForAnExplicitComparisonFollowUp() {
        var catalogTools = mock(CatalogTools.class);
        var expected = comparison(List.of(), List.of(pairResult()));
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary")).thenReturn(expected);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        var result = prefetcher.prefetch("¿Qué diferencia de precio hay entre esas dos versiones?",
                "[HISTORIAL DE CONVERSACIÓN]\n- usuario: Compara Ranger 2027 XL Gasolina 4x2 y XLT Gasolina 4x2.\n"
                        + "[/HISTORIAL DE CONVERSACIÓN]");

        assertThat(result).isPresent();
        verify(catalogTools).compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary");
    }

    @Test
    void rejectsComparisonWithMissingTrimEvidence() {
        var catalogTools = mock(CatalogTools.class);
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison(List.of("XLT GASOLINA 4X2"), List.of()));
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        assertThat(prefetcher.prefetch(
                "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2")).isEmpty();
        verify(catalogTools).compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary");
    }

    @Test
    void reportsOrdinaryTurnWithoutTreatingItAsAnUnresolvedComparison() {
        var catalogTools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        var result = prefetcher.prefetchResult("¿Qué precio tiene la Ranger?", "");

        assertThat(result.status()).isEqualTo(LocalReplayComparisonPrefetcher.Status.NOT_A_COMPARISON);
        assertThat(result.comparison()).isNull();
        verifyNoInteractions(catalogTools);
    }

    @Test
    void reportsUnresolvedComparisonFollowUpWithoutBuyerPair() {
        var catalogTools = mock(CatalogTools.class);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        var result = prefetcher.prefetchResult(
                "¿La comparación incluye alguna característica de seguridad para cada una?",
                "- usuario: Estoy revisando opciones para mi familia.");

        assertThat(result.status()).isEqualTo(LocalReplayComparisonPrefetcher.Status.PAIR_UNRESOLVED);
        assertThat(result.comparison()).isNull();
        verifyNoInteractions(catalogTools);
    }

    @Test
    void reportsCatalogResultIncompleteWhenOnlyOneComparisonPairIsNotReturned() {
        var catalogTools = mock(CatalogTools.class);
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary"))
                .thenReturn(comparison(List.of(), List.of()));
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        var result = prefetcher.prefetchResult(
                "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2", "");

        assertThat(result.status()).isEqualTo(LocalReplayComparisonPrefetcher.Status.CATALOG_RESULT_INCOMPLETE);
        assertThat(result.comparison()).isNull();
    }

    @Test
    void reportsReadyWhenExactPairHasOneComparisonEvenWithNoFactDifferences() {
        var catalogTools = mock(CatalogTools.class);
        var comparison = comparison(List.of(), List.of(pairResult()));
        when(catalogTools.compareTrims("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2"), "summary")).thenReturn(comparison);
        var prefetcher = new LocalReplayComparisonPrefetcher(
                new LocalComparisonPairResolver(), () -> ENTRIES, catalogTools);

        var result = prefetcher.prefetchResult(
                "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2", "");

        assertThat(result.status()).isEqualTo(LocalReplayComparisonPrefetcher.Status.READY);
        assertThat(result.comparison().comparison()).isSameAs(comparison);
    }

    private static CatalogTools.TrimComparison comparison(
            List<String> missingTrims, List<CatalogTools.TrimPairResult> comparisons) {
        return new CatalogTools.TrimComparison("RANGER", 2027, null, null,
                List.of(new CatalogTools.TrimResult("XL GASOLINA 4X2", BigDecimal.valueOf(775_000), "MXN",
                                null, null),
                        new CatalogTools.TrimResult("XLT GASOLINA 4X2", BigDecimal.valueOf(907_000), "MXN",
                                null, null)),
                comparisons, missingTrims, null);
    }

    private static CatalogTools.TrimPairResult pairResult() {
        return new CatalogTools.TrimPairResult("XL GASOLINA 4X2", "XLT GASOLINA 4X2",
                "source_backed_differences_found", BigDecimal.valueOf(132_000), List.of(), null);
    }
}
