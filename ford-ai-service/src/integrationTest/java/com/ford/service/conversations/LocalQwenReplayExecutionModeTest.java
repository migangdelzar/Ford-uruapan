package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenReplayExecutionModeTest {
    @Test
    void rejectsSeedConfigurationForFreshLiveAcceptance() {
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_REPLAY_MODE", "FRESH_FULL_LIVE",
                "FORD_QWEN_REPLAY_SEED_TRANSCRIPT", "prior.md")))
                .withMessageContaining("must not configure seed");
    }

    @Test
    void requiresASeedAndPrefixForSeededDiagnostic() {
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_REPLAY_MODE", "SEEDED_DIAGNOSTIC")))
                .withMessageContaining("requires seed transcript and prefix");
    }

    @Test
    void requiresCheckpointAndWindowForCheckpointMode() {
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_REPLAY_MODE", "CHECKPOINT_LIVE_WINDOW")))
                .withMessageContaining("requires checkpoint path and target window");
    }

    @Test
    void classifiesSeededModeOnlyWhenExplicitlyConfigured() {
        assertThat(LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_REPLAY_MODE", "SEEDED_DIAGNOSTIC",
                "FORD_QWEN_REPLAY_SEED_TRANSCRIPT", "prior.md",
                "FORD_QWEN_REPLAY_SEED_THROUGH_TURN", "86")))
                .isEqualTo(LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC);
    }

    @Test
    void hundredTurnOptInAloneDoesNotClaimFreshAcceptance() {
        assertThat(LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_100_REPLAY_ENABLED", "true")))
                .isEqualTo(LocalQwenReplayExecutionMode.LIVE_DIAGNOSTIC);
    }

    @Test
    void freshFullLiveRequiresExplicitHundredTurnOptIn() {
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_REPLAY_MODE", "FRESH_FULL_LIVE")))
                .withMessageContaining("explicit 100-turn opt-in");
    }

    @Test
    void resolvesExplicitBoundedLiveDiagnosticThroughTurnThirtyTwo() {
        var environment = Map.of(
                "FORD_QWEN_REPLAY_MODE", "BOUNDED_LIVE_DIAGNOSTIC",
                "FORD_QWEN_DIAGNOSTIC_END_TURN", "32");

        assertThat(LocalQwenReplayExecutionMode.resolve(environment))
                .isEqualTo(LocalQwenReplayExecutionMode.BOUNDED_LIVE_DIAGNOSTIC);
        assertThat(LocalQwenReplayExecutionMode.boundedDiagnosticEndTurn(environment)).isEqualTo(32);
    }

    @Test
    void rejectsMissingOrOutOfRangeBoundedDiagnosticEndTurn() {
        for (var value : java.util.List.of("0", "-1", "33", "nope")) {
            assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenReplayExecutionMode.resolve(Map.of(
                    "FORD_QWEN_REPLAY_MODE", "BOUNDED_LIVE_DIAGNOSTIC",
                    "FORD_QWEN_DIAGNOSTIC_END_TURN", value)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> LocalQwenReplayExecutionMode.resolve(Map.of(
                "FORD_QWEN_REPLAY_MODE", "BOUNDED_LIVE_DIAGNOSTIC")))
                .withMessageContaining("end turn");
    }

    @Test
    void boundedEndTurnRequiresExplicitBoundedMode() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                LocalQwenReplayExecutionMode.boundedDiagnosticEndTurn(Map.of(
                        "FORD_QWEN_DIAGNOSTIC_END_TURN", "32")))
                .withMessageContaining("explicit bounded diagnostic mode");
    }

    @Test
    void boundedDiagnosticRejectsSeedCheckpointAndHundredTurnSettings() {
        var base = Map.of(
                "FORD_QWEN_REPLAY_MODE", "BOUNDED_LIVE_DIAGNOSTIC",
                "FORD_QWEN_DIAGNOSTIC_END_TURN", "32");
        for (var extra : java.util.List.of(
                Map.of("FORD_QWEN_REPLAY_SEED_TRANSCRIPT", "prior.md"),
                Map.of("FORD_QWEN_REPLAY_CHECKPOINT", "checkpoint.json"),
                Map.of("FORD_QWEN_100_REPLAY_ENABLED", "true"))) {
            var environment = new java.util.HashMap<>(base);
            environment.putAll(extra);
            assertThatIllegalArgumentException().isThrownBy(() ->
                    LocalQwenReplayExecutionMode.resolve(environment));
        }
    }
}
