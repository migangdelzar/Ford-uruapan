package com.ford.service.conversations;

import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.ai.application.DecisionTraceCollector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionTraceEventTest {

    @Test
    void rejectsUnknownStageAndOutcomeValues() {
        assertThatThrownBy(() -> event("free_text_stage", "accepted"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event("answer", "accepted free text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundedStageAndTerminalOutcomeVocabulary() {
        for (var stage : List.of("security", "classifier", "entity_resolution", "scope", "authorization",
                "route", "tool", "evidence", "answer", "quality", "handoff", "finalization")) {
            assertThat(event(stage, "not_applicable").stage()).isEqualTo(stage);
        }
        for (var outcome : List.of("blocked", "allowed", "ambiguous", "denied", "authorized", "unknown",
                "supported", "conflicted", "clarification", "pass", "repair", "handoff", "finalized")) {
            assertThat(event("quality", outcome).outcome()).isEqualTo(outcome);
        }
    }

    @Test
    void collectorPreservesOrderAndReturnsImmutableSnapshot() {
        var collector = new DecisionTraceCollector();
        var first = event("classifier", "completed");
        var second = event("tool", "returned");

        collector.record(first);
        collector.record(second);
        var snapshot = collector.snapshot();

        assertThat(snapshot).containsExactly(first, second);
        assertThatThrownBy(() -> snapshot.add(first)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void eventHasNoFreeFormMessagePromptOrToolResultFields() {
        var names = List.of(DecisionTraceEvent.class.getRecordComponents()).stream()
                .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT)).toList();

        assertThat(names).noneMatch(name -> name.contains("message") || name.contains("prompt")
                || name.contains("reply") || name.contains("result") || name.contains("content")
                || name.contains("argument") || name.contains("reasoning"));
    }

    private static DecisionTraceEvent event(String stage, String outcome) {
        return new DecisionTraceEvent(stage, "test_reason", "catalog", "answer", "search_vehicles",
                outcome, 1, Instant.parse("2026-10-21T12:00:00Z"));
    }
}
