package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalReplayVehicleFactPrefetcherTest {
    private static final List<VehicleNameIndexEntry> ENTRIES = List.of(
            new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"),
            new VehicleNameIndexEntry("BRONCO", 2027, "BIG BEND"));
    private final CatalogTools tools = mock(CatalogTools.class);
    private final LocalReplayVehicleFactPrefetcher prefetcher =
            new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> ENTRIES, tools);

    @Test void fetchesExactIdentityFromBuyerHistory() {
        var memory = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: Ranger 2027 XLT Gasolina 4x2\n[FIN DEL HISTORIAL]";
        var evidence = evidence("RANGER", 2027, "XLT GASOLINA 4X2", List.of(new VehicleFact("spec", "motor", "2.3", null, null)));
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2")).thenReturn(evidence);
        assertThat(prefetcher.prefetch("¿Qué fecha tiene el precio?", memory)).isPresent();
        verify(tools).getVehicle("RANGER", 2027, "XLT GASOLINA 4X2");
    }

    @Test void unresolvedOrAmbiguousIdentityDoesNotCallCatalog() {
        assertThat(prefetcher.prefetch("¿Qué fecha tiene el precio?", "")).isEmpty();
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2 o Bronco 2027 Big Bend", "")).isEmpty();
        verifyNoInteractions(tools);
    }

    @Test void rejectsUnavailableMismatchAndEvidenceWithoutFactsOrSources() {
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(new CatalogTools.UnavailableModelYear("RANGER", 2027, "missing", List.of(), List.of()));
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2", "")).isEmpty();
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(evidence("BRONCO", 2027, "XLT GASOLINA 4X2", List.of()));
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2", "")).isEmpty();
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(evidence("RANGER", 2026, "XLT GASOLINA 4X2", List.of()));
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2", "")).isEmpty();
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(evidence("RANGER", 2027, "XL GASOLINA 4X2", List.of()));
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2", "")).isEmpty();
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2")).thenReturn(null);
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2", "")).isEmpty();
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(evidence("RANGER", 2027, "XLT GASOLINA 4X2", List.of()));
        assertThat(prefetcher.prefetch("Ranger 2027 XLT Gasolina 4x2", "")).isEmpty();
    }

    @Test void fetchesBothExactVehiclesForDimensionFollowUpOnAResolvedComparisonPair() {
        when(tools.getVehicle("RANGER", 2027, "XL GASOLINA 4X2"))
                .thenReturn(evidence("RANGER", 2027, "XL GASOLINA 4X2",
                        List.of(new VehicleFact("spec", "price", "775000", null, null))));
        when(tools.getVehicle("RANGER", 2027, "XLT GASOLINA 4X2"))
                .thenReturn(evidence("RANGER", 2027, "XLT GASOLINA 4X2",
                        List.of(new VehicleFact("spec", "price", "907000", null, null))));

        var facts = prefetcher.prefetch(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));

        assertThat(facts).hasSize(2).allSatisfy(fact -> assertThat(fact.evidenceCount()).isPositive());
        verify(tools).getVehicle("RANGER", 2027, "XL GASOLINA 4X2");
        verify(tools).getVehicle("RANGER", 2027, "XLT GASOLINA 4X2");
    }

    @Test void refusesUntrimmedIdentityInsteadOfDereferencingOrUsingGenericEvidence() {
        var genericEntry = List.of(new VehicleNameIndexEntry("RANGER", 2027, null));
        var genericPrefetcher = new LocalReplayVehicleFactPrefetcher(
                new LocalBuyerVehicleResolver(), () -> genericEntry, tools);

        assertThat(genericPrefetcher.prefetch("Ranger 2027", "")).isEmpty();

        verifyNoInteractions(tools);
    }

    private static VehicleEvidence evidence(String model, Integer year, String trim, List<VehicleFact> facts) {
        return new VehicleEvidence(model, year, trim, null, null, null, null, null, facts, List.of());
    }
}
