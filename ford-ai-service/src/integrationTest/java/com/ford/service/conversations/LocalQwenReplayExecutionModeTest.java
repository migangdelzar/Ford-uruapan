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
}
