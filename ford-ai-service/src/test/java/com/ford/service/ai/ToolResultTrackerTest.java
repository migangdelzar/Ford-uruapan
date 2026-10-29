package com.ford.service.ai;

import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.ai.application.ToolExecutionSnapshot;
import com.ford.service.ai.application.ToolResultTracker;
import com.ford.service.ai.application.EvidenceResult;
import com.ford.service.ai.application.VehicleTrimCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultTrackerTest {
    @Test
    void snapshotsEachToolAttemptAsReturnedEmptyOrFailed() {
        var tracker = new ToolResultTracker();
        tracker.attempted("get_vehicle");
        tracker.attempted("search_vehicles");
        tracker.resultReturned("search_vehicles", "{\"results\":[{\"sourceUrl\":\"https://ford.example/a\"}]}");
        tracker.attempted("list_promotions");
        tracker.failed("list_promotions", AssistantTurnUnavailableException.Disposition.RETRY);

        var snapshot = tracker.snapshot();

        assertThat(snapshot.calls()).containsExactly(
                new ToolExecutionSnapshot.ToolCall("get_vehicle", ToolExecutionSnapshot.Status.EMPTY,
                        "tool_return_missing", null),
                new ToolExecutionSnapshot.ToolCall("list_promotions", ToolExecutionSnapshot.Status.FAILED,
                        "retry", null),
                new ToolExecutionSnapshot.ToolCall("search_vehicles", ToolExecutionSnapshot.Status.RETURNED,
                        "tool_returned", 1));
        assertThat(EvidenceResult.from(snapshot)).isEqualTo(EvidenceResult.supported(List.of("tool:search_vehicles"), 1));
    }

    @Test
    void emptyArrayResultDoesNotCountAsEvidence() {
        var tracker = new ToolResultTracker();
        tracker.attempted("get_vehicle");
        tracker.resultReturned("get_vehicle", "{\"facts\":[],\"sourceUrl\":null}");

        assertThat(tracker.snapshot().calls()).singleElement().satisfies(call -> {
            assertThat(call.status()).isEqualTo(ToolExecutionSnapshot.Status.RETURNED);
            assertThat(call.evidenceCount()).isZero();
        });
        assertThat(tracker.hasEvidence()).isFalse();
        assertThat(EvidenceResult.from(tracker.snapshot())).isEqualTo(EvidenceResult.unknown());
    }

    @Test
    void recognizesAmbiguousVehicleTrimAsClarificationSignalWithoutSelectingChoice() {
        var tracker = new ToolResultTracker();
        tracker.attempted("get_vehicle");
        tracker.resultReturned("get_vehicle", "{\"model\":\"RANGER\",\"modelYear\":2027,"
                + "\"choices\":[{\"trim\":\"XLT Gasolina 4x2\"},{\"trim\":\"XLT Diésel 4x4\"}]}");

        assertThat(tracker.hasAmbiguousVehicleTrim()).isTrue();
        assertThat(tracker.ambiguousVehicleTrim()).hasValueSatisfying(result -> {
            assertThat(result.model()).isEqualTo("RANGER");
            assertThat(result.year()).isEqualTo(2027);
            assertThat(result.choices()).extracting(VehicleTrimCandidate::trim)
                    .containsExactly("XLT Gasolina 4x2", "XLT Diésel 4x4");
        });
    }

    @Test
    void executionSnapshotCopiesItsCallList() {
        var calls = new java.util.ArrayList<ToolExecutionSnapshot.ToolCall>();
        var snapshot = new ToolExecutionSnapshot(calls);

        assertThatThrownBy(() -> snapshot.calls().add(new ToolExecutionSnapshot.ToolCall("get_vehicle",
                ToolExecutionSnapshot.Status.EMPTY, "tool_return_missing", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
