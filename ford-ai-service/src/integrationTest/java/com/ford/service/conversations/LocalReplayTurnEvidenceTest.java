package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayTurnEvidenceTest {
    private static final List<VehicleNameIndexEntry> CURRENT_ENTRIES = List.of(
            new VehicleNameIndexEntry("Ranger", 2027, "XLT Gasolina 4x2"));

    @Test
    void priorMessageEvidenceCannotSatisfyCurrentTurnCatalogFact() {
        var priorEvidence = List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 1));
        var byProviderMessage = Map.of("prior-message", priorEvidence, "current-message",
                List.<LocalQwenTranscriptWriter.ToolTrace>of());

        var currentEvidence = LocalReplayTurnEvidence.forTurn("current-message", byProviderMessage);

        assertThat(currentEvidence).isEmpty();
        var violations = LocalReplaySafetyAssertions.evaluate(
                new NaturalBuyerConversationScenario.Turn(2, "catalog fact",
                        NaturalBuyerConversationScenario.Expectation.CATALOG_FACT),
                "Respuesta sintética", currentEvidence, List.of(), CURRENT_ENTRIES, List.of());
        assertThat(violations).contains(LocalReplaySafetyAssertions.Violation.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void selectsOnlyExactCurrentProviderMessageEvents() {
        var priorEvidence = List.of(new LocalQwenTranscriptWriter.ToolTrace("get_vehicle", "returned", 1));
        var currentEvidence = List.of(new LocalQwenTranscriptWriter.ToolTrace("search_vehicles", "returned", 2));
        var byProviderMessage = Map.of("prior-message", priorEvidence, "current-message", currentEvidence);

        assertThat(LocalReplayTurnEvidence.forTurn("current-message", byProviderMessage)).containsExactlyElementsOf(
                currentEvidence);
        assertThat(LocalReplayTurnEvidence.forTurn("missing-message", byProviderMessage)).isEmpty();
        assertThat(LocalReplayTurnEvidence.forTurn(null, byProviderMessage)).isEmpty();
        assertThat(LocalReplayTurnEvidence.forTurn("current-message", null)).isEmpty();
    }
}
