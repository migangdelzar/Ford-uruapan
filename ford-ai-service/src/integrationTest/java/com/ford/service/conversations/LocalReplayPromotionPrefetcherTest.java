package com.ford.service.conversations;

import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.*;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayPromotionPrefetcherTest {
    @Test
    void queriesBuyerIdentityExactlyAndPreservesPromotionSourceAndConditions() {
        var tools = mock(CatalogTools.class);
        var promotion = promotion("RANGER", 2027);
        when(tools.listPromotions("RANGER", 2027, null, false, 20, 0, "full", null))
                .thenReturn(new CatalogTools.PromotionSearch(1, 0, 20, false, List.of(promotion)));
        var prefetcher = new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(),
                () -> List.of(new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2")), tools);
        var history = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: RANGER 2027 XLT GASOLINA 4X2\n[FIN DEL HISTORIAL]";

        var result = prefetcher.prefetch("¿Ese monto corresponde a la XLT que acabamos de revisar?", history);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().evidenceCount()).isEqualTo(1);
        assertThat(result.orElseThrow().promotions().getFirst().sourceUrl()).isEqualTo("https://ford.test/promo");
        assertThat(result.orElseThrow().promotions().getFirst().conditions()).isEqualTo("Sujeto a aprobación");
        verify(tools).listPromotions("RANGER", 2027, null, false, 20, 0, "full", null);
    }

    @Test
    void ambiguousOrMissingBuyerIdentityDoesNotQueryPromotions() {
        var tools = mock(CatalogTools.class);
        var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, "XLT"),
                new VehicleNameIndexEntry("BRONCO", 2026, "BIG BEND"));
        var prefetcher = new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(), () -> entries, tools);
        var ambiguousHistory = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: RANGER 2027 XLT y BRONCO 2026 BIG BEND\n[FIN DEL HISTORIAL]";

        assertThat(prefetcher.prefetch("¿Ese monto aplica?", ambiguousHistory)).isEmpty();
        assertThat(prefetcher.prefetch("¿Ese monto aplica?", null)).isEmpty();
        verify(tools, never()).listPromotions(anyString(), any(), any(), anyBoolean(), anyInt(), anyInt(), anyString(), any());
    }

    @Test
    void emptyOrMismatchedPromotionsFailClosed() {
        var tools = mock(CatalogTools.class);
        var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, "XLT"));
        when(tools.listPromotions(anyString(), any(), any(), anyBoolean(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(new CatalogTools.PromotionSearch(0, 0, 20, false, List.of()))
                .thenReturn(new CatalogTools.PromotionSearch(1, 0, 20, false, List.of(promotion("BRONCO", 2026))));
        var prefetcher = new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(), () -> entries, tools);
        var history = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: RANGER 2027 XLT\n[FIN DEL HISTORIAL]";

        assertThat(prefetcher.prefetch("¿Y la promoción?", history)).isEmpty();
        assertThat(prefetcher.prefetch("¿Y la promoción?", history)).isEmpty();
    }

    private static CatalogTools.PromotionResult promotion(String model, int year) {
        return new CatalogTools.PromotionResult("p1", model, year, "Promoción", null, null, null, "MXN",
                null, "Sujeto a aprobación", null, LocalDate.now(), null, "active",
                "https://ford.test/promo", null, null, null, null);
    }
}
