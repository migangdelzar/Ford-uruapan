package com.ford.service.ai.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Mutable only within one turn; callers receive immutable, insertion-ordered snapshots. */
public final class DecisionTraceCollector {
    private final List<DecisionTraceEvent> events = new ArrayList<>();

    public synchronized void record(DecisionTraceEvent event) {
        events.add(Objects.requireNonNull(event, "decision trace event is required"));
    }

    public synchronized List<DecisionTraceEvent> snapshot() {
        return List.copyOf(events);
    }
}
