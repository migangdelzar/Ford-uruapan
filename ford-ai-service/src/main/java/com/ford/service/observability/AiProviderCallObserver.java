package com.ford.service.observability;

/** Receives content-free provider-call measurements; implementations must not add prompt or response text. */
@FunctionalInterface
public interface AiProviderCallObserver {
    AiProviderCallObserver NOOP = measurement -> { };

    void record(ProviderCallMeasurement measurement);

    record ProviderCallMeasurement(String provider, String model, long durationMillis,
                                   Integer promptTokens, Integer completionTokens,
                                   TokenUsageStatus tokenUsageStatus, int authorizedToolCount,
                                   int memoryContextBytes, boolean repairAttempt, Outcome outcome) {
        public ProviderCallMeasurement {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("provider is required");
            }
            if (model == null || model.isBlank()) model = "unknown";
            if (durationMillis < 0 || authorizedToolCount < 0 || memoryContextBytes < 0) {
                throw new IllegalArgumentException("provider measurement values must be nonnegative");
            }
            if (tokenUsageStatus == null || outcome == null) {
                throw new IllegalArgumentException("usage status and call outcome are required");
            }
            if (promptTokens != null && promptTokens < 0 || completionTokens != null && completionTokens < 0) {
                throw new IllegalArgumentException("token counts must be nonnegative");
            }
            if (tokenUsageStatus == TokenUsageStatus.ACTUAL
                    && (promptTokens == null || completionTokens == null)) {
                throw new IllegalArgumentException("actual token usage requires prompt and completion counts");
            }
            if (tokenUsageStatus == TokenUsageStatus.ESTIMATED
                    && (promptTokens == null || completionTokens == null)) {
                throw new IllegalArgumentException("estimated token usage requires both estimated counts");
            }
            if (tokenUsageStatus == TokenUsageStatus.UNAVAILABLE
                    && (promptTokens != null || completionTokens != null)) {
                throw new IllegalArgumentException("unavailable token usage must not contain counts");
            }
        }
    }

    enum TokenUsageStatus { ACTUAL, ESTIMATED, UNAVAILABLE }

    enum Outcome { RETURNED, FAILED }
}
