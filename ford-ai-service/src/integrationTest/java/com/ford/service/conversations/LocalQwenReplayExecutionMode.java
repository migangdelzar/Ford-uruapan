package com.ford.service.conversations;

import java.util.Map;
import java.util.Objects;

/** Fail-closed execution lane selection for the local synthetic Qwen harness. */
enum LocalQwenReplayExecutionMode {
    SEEDED_DIAGNOSTIC,
    CHECKPOINT_LIVE_WINDOW,
    FRESH_FULL_LIVE,
    LIVE_DIAGNOSTIC;

    static LocalQwenReplayExecutionMode resolve(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment is required");
        var configured = environment.get("FORD_QWEN_REPLAY_MODE");
        var seedPath = nonBlank(environment.get("FORD_QWEN_REPLAY_SEED_TRANSCRIPT"));
        var checkpointPath = nonBlank(environment.get("FORD_QWEN_REPLAY_CHECKPOINT"));
        var hasSeedSettings = seedPath != null
                || nonBlank(environment.get("FORD_QWEN_REPLAY_SEED_THROUGH_TURN")) != null
                || nonBlank(environment.get("FORD_QWEN_REPLAY_FORCE_LIVE_TURNS")) != null;

        LocalQwenReplayExecutionMode mode;
        if (configured != null && !configured.isBlank()) {
            try {
                mode = valueOf(configured.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("unsupported Qwen replay mode", failure);
            }
        } else if (seedPath != null) {
            mode = SEEDED_DIAGNOSTIC;
        } else if (checkpointPath != null) {
            mode = CHECKPOINT_LIVE_WINDOW;
        } else {
            mode = LIVE_DIAGNOSTIC;
        }

        switch (mode) {
            case SEEDED_DIAGNOSTIC -> {
                if (seedPath == null || nonBlank(environment.get("FORD_QWEN_REPLAY_SEED_THROUGH_TURN")) == null) {
                    throw new IllegalArgumentException("SEEDED_DIAGNOSTIC requires seed transcript and prefix");
                }
                if (checkpointPath != null) {
                    throw new IllegalArgumentException("seeded diagnostic must not configure checkpoint path");
                }
            }
            case CHECKPOINT_LIVE_WINDOW -> {
                if (checkpointPath == null || !validPositiveInteger(environment.get("FORD_QWEN_TARGET_START_TURN"))
                        || !validPositiveInteger(environment.get("FORD_QWEN_TARGET_END_TURN"))) {
                    throw new IllegalArgumentException("CHECKPOINT_LIVE_WINDOW requires checkpoint path and target window");
                }
                var start = Integer.parseInt(environment.get("FORD_QWEN_TARGET_START_TURN"));
                var end = Integer.parseInt(environment.get("FORD_QWEN_TARGET_END_TURN"));
                if (end < start) throw new IllegalArgumentException("checkpoint target window is reversed");
                if (hasSeedSettings) {
                    throw new IllegalArgumentException("checkpoint live window must not configure seed");
                }
            }
            case FRESH_FULL_LIVE -> {
                if (hasSeedSettings || checkpointPath != null) {
                    throw new IllegalArgumentException("FRESH_FULL_LIVE must not configure seed or checkpoint");
                }
                if (!"true".equalsIgnoreCase(environment.get("FORD_QWEN_100_REPLAY_ENABLED"))) {
                    throw new IllegalArgumentException("FRESH_FULL_LIVE requires the explicit 100-turn opt-in");
                }
            }
            case LIVE_DIAGNOSTIC -> {
                if (hasSeedSettings || checkpointPath != null) {
                    throw new IllegalArgumentException("LIVE_DIAGNOSTIC must not configure seed or checkpoint");
                }
            }
        }
        return mode;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean validPositiveInteger(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) return false;
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException failure) {
            return false;
        }
    }
}
