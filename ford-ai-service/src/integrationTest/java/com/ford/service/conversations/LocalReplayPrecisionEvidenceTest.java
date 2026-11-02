package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalReplayPrecisionEvidenceTest {
    @Test
    void retainsOnlyEvidenceRecordedForCurrentTurn() {
        var collector = new LocalReplayPrecisionEvidence();
        var conversation = UUID.randomUUID();
        collector.record(conversation, "old buyer turn evidence");
        collector.beginTurn(conversation);
        collector.record(conversation, "current evidence one");
        collector.record(conversation, "current evidence two");
        collector.recordToolEvidence(conversation, "compare_trims", 1, "current comparison payload");

        assertThat(collector.currentTurnEvidence(conversation))
                .containsExactly("current evidence one", "current evidence two", "current comparison payload");
        assertThat(collector.currentTurnToolTraces(conversation)).containsExactly(
                new LocalQwenTranscriptWriter.ToolTrace("compare_trims", "returned", 1,
                        LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH));
        assertThatThrownBy(() -> collector.currentTurnEvidence(conversation).add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> collector.currentTurnToolTraces(conversation).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
