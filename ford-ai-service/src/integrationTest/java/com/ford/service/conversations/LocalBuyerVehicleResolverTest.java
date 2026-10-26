package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalBuyerVehicleResolverTest {
    private final LocalBuyerVehicleResolver resolver = new LocalBuyerVehicleResolver();
    private final List<VehicleNameIndexEntry> entries = List.of(
            new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"),
            new VehicleNameIndexEntry("BRONCO", 2027, "BIG BEND"));

    @Test
    void resolvesBuyerIdentityFromHistoryAndIgnoresAssistant() {
        var memory = "[HISTORIAL DE CONVERSACIÓN]\nHistorial reciente:\n"
                + "- usuario: Revisemos Ranger 2027 XLT Gasolina 4x2.\n"
                + "- asistente: El catálogo confirma Ranger 2027 XLT Gasolina 4x2.\n"
                + "[FIN DEL HISTORIAL]";
        assertThat(resolver.resolve("¿Qué fecha tiene el precio?", memory, entries))
                .contains(new LocalBuyerVehicleResolver.VehicleRef("RANGER", 2027, "XLT GASOLINA 4X2"));
    }

    @Test
    void currentBuyerIdentityWins() {
        assertThat(resolver.resolve("Busco Bronco 2027 Big Bend", "", entries))
                .contains(new LocalBuyerVehicleResolver.VehicleRef("BRONCO", 2027, "BIG BEND"));
    }

    @Test
    void assistantOnlyIdentityDoesNotResolve() {
        assertThat(resolver.resolve("¿Y el precio?", "- asistente: Ranger 2027 XLT Gasolina 4x2", entries)).isEmpty();
    }

    @Test
    void nearestBuyerLineWithMultipleExactIdentitiesIsAmbiguous() {
        var memory = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: Quiero Ranger 2027 XLT Gasolina 4x2\n"
                + "- usuario: Ranger 2027 XLT Gasolina 4x2 o Bronco 2027 Big Bend\n[FIN DEL HISTORIAL]";
        assertThat(resolver.resolve("¿Cuál recomiendas?", memory, entries)).isEmpty();
    }

    @Test
    void currentMessageAmbiguityDoesNotFallBackToHistory() {
        var memory = "[HISTORIAL DE CONVERSACIÓN]\n- usuario: Ranger 2027 XLT Gasolina 4x2\n[FIN DEL HISTORIAL]";
        assertThat(resolver.resolve("Ranger 2027 XLT Gasolina 4x2 o Bronco 2027 Big Bend", memory, entries)).isEmpty();
    }

    @Test
    void blankAndNoMatchReturnEmpty() {
        assertThat(resolver.resolve("", "", entries)).isEmpty();
        assertThat(resolver.resolve("Me gusta la Ranger", "", entries)).isEmpty();
    }

    @Test
    void normalizesCaseAndRepeatedWhitespaceWithoutPartialTrimMatching() {
        assertThat(resolver.resolve("RANGER   2027   xlt   gasolina 4x2", "", entries))
                .contains(new LocalBuyerVehicleResolver.VehicleRef("RANGER", 2027, "XLT GASOLINA 4X2"));
        assertThat(resolver.resolve("Ranger 2027 XLT", "", entries)).isEmpty();
    }
}
