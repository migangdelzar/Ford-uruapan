package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.conversations.application.CustomerChoicePresentationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplaySafetyAssertionsTest {
    private static final List<VehicleNameIndexEntry> CURRENT_ENTRIES = List.of(
            new VehicleNameIndexEntry("Ranger", 2027, "XLT Gasolina 4x2"));

    @Test
    void requiresReturnedVehicleEvidenceForCatalogFactTurns() {
        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.CATALOG_FACT), "Respuesta sintética",
                List.of(), List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void priorTurnVehicleEvidenceDoesNotCountAsCurrentTurnEvidence() {
        var priorTurnEvidence = List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 1));
        var currentTurnEvidence = List.<LocalQwenTranscriptWriter.ToolTrace>of();

        assertThat(priorTurnEvidence).hasSize(1);
        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.CATALOG_FACT), "Respuesta sintética",
                currentTurnEvidence, List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void replayPrefetchVehicleEvidenceIsAcceptedOnlyInPrefetchModeForCatalogFacts() {
        var turn = turn(NaturalBuyerConversationScenario.Expectation.CATALOG_FACT);
        var returnedPrefetch = List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 1,
                LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));

        assertThat(LocalReplaySafetyAssertions.evaluate(turn, "Respuesta sintética", returnedPrefetch,
                List.of(), CURRENT_ENTRIES, List.of())).contains(
                LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
        assertThat(LocalReplaySafetyAssertions.evaluate(turn, "Respuesta sintética", returnedPrefetch,
                List.of(), CURRENT_ENTRIES, List.of(), true)).isEmpty();
        assertThat(LocalReplaySafetyAssertions.evaluate(turn, "Respuesta sintética", List.of(
                new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 0,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH)),
                List.of(), CURRENT_ENTRIES, List.of(), true)).contains(
                LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void requiresPromotionEvidenceForOfferTurns() {
        var traces = List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 2));

        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.OFFER), "Respuesta sintética",
                traces, List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void requiresComparisonEvidenceForComparisonTurns() {
        var traces = List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 1));

        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.COMPARISON), "Respuesta sintética",
                traces, List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void rawModeDoesNotAcceptReplayPrefetchAsModelSelectedComparisonEvidence() {
        var prefetch = List.of(new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));

        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.COMPARISON), "Comparación sintética",
                prefetch, List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void explicitPrefetchModeAcceptsOnlySuccessfulPrefetchComparisonEvidence() {
        var prefetch = List.of(new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
        var turn = turn(NaturalBuyerConversationScenario.Expectation.COMPARISON);

        assertThat(LocalReplaySafetyAssertions.evaluate(turn, "Comparación sintética", prefetch,
                List.of(), CURRENT_ENTRIES, List.of(), true)).isEmpty();
        assertThat(LocalReplaySafetyAssertions.evaluate(turn, "Comparación sintética", List.of(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "empty", 0,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH)),
                List.of(), CURRENT_ENTRIES, List.of(), true))
                .contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void rejectsBroadVehicleSearchAsEvidenceForPublishedPriceComparisons() {
        var traces = List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 5));

        var violations = LocalReplaySafetyAssertions.evaluate(new NaturalBuyerConversationScenario.Turn(25,
                        "¿Cuál de las dos tiene el precio publicado menor según estos datos?",
                        NaturalBuyerConversationScenario.Expectation.COMPARISON),
                "La Ranger XL tiene el precio publicado menor.", traces, List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void rejectsUnrequestedCatalogModelsInFollowUpAboutTheComparedPair() {
        var entries = List.of(
                new VehicleNameIndexEntry("Ranger", 2027, "XL Gasolina 4x2"),
                new VehicleNameIndexEntry("Expedition", 2025, null),
                new VehicleNameIndexEntry("E Transit", 2026, null),
                new VehicleNameIndexEntry("F 250", 2026, null),
                new VehicleNameIndexEntry("Transit Custom", 2026, null),
                new VehicleNameIndexEntry("Bronco Raptor", 2026, null));
        var answer = "Expedition, E Transit, F 250, Transit Custom y Bronco Raptor";
        var traces = List.of(new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1));

        var violations = LocalReplaySafetyAssertions.evaluate(new NaturalBuyerConversationScenario.Turn(25,
                        "¿Cuál de las dos tiene el precio publicado menor según estos datos?",
                        NaturalBuyerConversationScenario.Expectation.COMPARISON),
                answer, traces, List.of(), entries, List.of());

        assertThat(violations).extracting(Enum::name).contains("OUT_OF_SCOPE_COMPARISON");
    }

    @Test
    void requiresCatalogEvidenceBeforeNamingARecommendationDuringDiscovery() {
        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                "Le recomiendo Ranger 2027 para ese uso.", List.of(), List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void requiresCatalogEvidenceBeforeOfferingCatalogReplyOptionsDuringDiscovery() {
        var options = new CustomerChoicePresentationService().fromAnswer(
                "Ranger 2027 XLT Gasolina 4x2", CURRENT_ENTRIES);

        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                "¿Cuál te interesa?", List.of(), options, CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void acceptsOnlyReplyOptionsResolvingToCurrentCatalogIdentity() {
        var valid = new CustomerChoicePresentationService().fromAnswer(
                "Ranger 2027 XLT Gasolina 4x2", CURRENT_ENTRIES);
        var stale = List.of(new ReplyOption("vehicle-0000000000000000", "Ranger 2027 XLT Gasolina 4x2"));
        var evidence = List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 1));

        assertThat(LocalReplaySafetyAssertions.evaluate(turn(NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                "Ranger 2027 XLT Gasolina 4x2", evidence, valid, CURRENT_ENTRIES, List.of())).isEmpty();
        assertThat(LocalReplaySafetyAssertions.evaluate(turn(NaturalBuyerConversationScenario.Expectation.DISCOVERY),
                "Ranger 2027 XLT Gasolina 4x2", evidence, stale, CURRENT_ENTRIES, List.of()))
                .contains(LocalReplaySafetyAssertions.Violation.UNGROUNDED_CHOICE);
    }

    @Test
    void rejectsInventoryStatusClaimsWithoutExactApprovedText() {
        var answer = "Sí, hay disponibilidad para esa versión.";

        var violations = LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.UNAVAILABLE_CAPABILITY), answer,
                List.of(), List.of(), CURRENT_ENTRIES, List.of());

        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.UNAPPROVED_INVENTORY_CLAIM);
    }

    @Test
    void acceptsNeutralInventoryDeferralAndExactApprovedInventoryText() {
        var neutral = "No puedo confirmar disponibilidad; un asesor debe verificarla.";
        var approved = "Disponibilidad confirmada por asesor.";

        assertThat(LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.UNAVAILABLE_CAPABILITY), neutral,
                List.of(), List.of(), CURRENT_ENTRIES, List.of())).isEmpty();
        assertThat(LocalReplaySafetyAssertions.evaluate(turn(
                        NaturalBuyerConversationScenario.Expectation.UNAVAILABLE_CAPABILITY), approved,
                List.of(), List.of(), CURRENT_ENTRIES, List.of(approved))).isEmpty();
    }

    private static NaturalBuyerConversationScenario.Turn turn(
            NaturalBuyerConversationScenario.Expectation expectation) {
        return new NaturalBuyerConversationScenario.Turn(1, "synthetic buyer turn", expectation);
    }
}
