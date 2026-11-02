package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalComparisonPairResolverTest {
    private final LocalComparisonPairResolver resolver = new LocalComparisonPairResolver();

    @Test
    void resolvesExactlyNamedTrimsCaseInsensitively() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

        assertThat(resolver.resolve(
                "Compara Ranger 2027 XL Gasolina 4x2 con Ranger 2027 XLT Gasolina 4x2", entries))
                .contains(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                        List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));
    }

    @Test
    void resolvesSecondTrimWhenModelAndYearAreStatedOnceForThePair() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

        assertThat(resolver.resolve(
                "Compara únicamente Ranger 2027 XLT Gasolina 4x2 y XL Gasolina 4x2, sin agregar otras versiones.",
                entries)).contains(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));
    }

    @Test
    void resolvesBuyerPairForFollowUpAskingAboutEachVersion() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

        assertThat(resolver.resolveFollowUp(
                "¿La comparación incluye alguna característica de seguridad para cada una?",
                "- usuario: Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.", entries))
                .contains(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                        List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));
    }

    @Test
    void resolvesBuyerPairForFollowUpWithoutRepeatedModelYear() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

        assertThat(resolver.resolveFollowUp(
                "¿Qué características confirmadas distinguen a la Ranger XLT de la XL Gasolina 4x2?",
                "- usuario: Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.", entries))
                .contains(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                        List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));
    }

    @Test
    void resolvesComparisonPairFromHistoryWhenQuestionAsksWhatDataIsMissing() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"));

        assertThat(resolver.resolveFollowUp("¿Qué dato no está disponible para hacer una comparación completa?",
                "[HISTORIAL DE CONVERSACIÓN]\n- usuario: Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.\n"
                        + "- usuario: ¿Qué diferencia de precio hay entre esas dos versiones?\n"
                        + "- usuario: ¿Qué características distinguen a la Ranger XLT de la XL Gasolina 4x2?\n"
                        + "[/HISTORIAL DE CONVERSACIÓN]", entries))
                .contains(new LocalComparisonPairResolver.Pair("RANGER", 2027,
                        List.of("XL GASOLINA 4X2", "XLT GASOLINA 4X2")));
    }

    @Test
    void distinguishesComparisonIntentFromOrdinaryVehicleQuestion() {
        assertThat(resolver.isComparisonIntent("¿Qué precio tiene la Ranger?")).isFalse();
        assertThat(resolver.isComparisonIntent("Compara Ranger 2027 XL y XLT")).isTrue();
        assertThat(resolver.isComparisonIntent("¿La comparación incluye seguridad para cada una?")).isTrue();
    }

    @Test
    void rejectsPairWithOnlyOneExactTrim() {
        var entries = List.of(new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"));

        assertThat(resolver.resolve("Compara Ranger 2027 XL Gasolina 4x2", entries)).isEmpty();
    }

    @Test
    void rejectsMoreThanTwoExactTrims() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "WILDTRAK DIESEL 4X4"));

        assertThat(resolver.resolve("Compare Ranger 2027 XL Gasolina 4x2, XLT Gasolina 4x2 and Wildtrak Diesel 4x4",
                entries)).isEmpty();
    }

    @Test
    void rejectsPairsWithDifferentModelsOrYears() {
        var differentModel = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("LOBO", 2027, "XL GASOLINA 4X2"));
        var differentYear = List.of(
                new VehicleNameIndexEntry("RANGER", 2026, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"));

        assertThat(resolver.resolve("Compara Ranger 2027 XL Gasolina 4x2 con Lobo 2027 XL Gasolina 4x2",
                differentModel)).isEmpty();
        assertThat(resolver.resolve("Compara Ranger 2026 XL Gasolina 4x2 con Ranger 2027 XL Gasolina 4x2",
                differentYear)).isEmpty();
    }

    @Test
    void rejectsUnrelatedMessageAndEmptyVersionNames() {
        var entries = List.of(
                new VehicleNameIndexEntry("RANGER", 2027, "XL GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, "XLT GASOLINA 4X2"),
                new VehicleNameIndexEntry("RANGER", 2027, null),
                new VehicleNameIndexEntry("RANGER", 2027, "  "));

        assertThat(resolver.resolve("¿Qué precio tiene la Ranger?", entries)).isEmpty();
    }
}
