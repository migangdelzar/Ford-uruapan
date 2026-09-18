package com.ford.service.observability;

public interface AiMetrics {
    void modelCompleted(ModelObservation observation);
    void toolCompleted(ToolObservation observation);
    void handoffCreated(HandoffObservation observation);

    record ModelObservation(String provider, String model, boolean success, long durationMs) {}
    record ToolObservation(String tool, boolean success, long durationMs) {}
    record HandoffObservation(String reason, String band) {}
}
