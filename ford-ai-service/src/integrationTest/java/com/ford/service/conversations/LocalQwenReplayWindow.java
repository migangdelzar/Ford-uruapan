package com.ford.service.conversations;

import java.util.List;
import java.util.Objects;

/** Runs a bounded natural-buyer prefix without changing replay acceptance semantics. */
final class LocalQwenReplayWindow {
    private static final int MAX_DIAGNOSTIC_TURN = 32;

    private LocalQwenReplayWindow() { }

    static void run(List<NaturalBuyerConversationScenario.Turn> turns, int endTurn, TurnHandler handler)
            throws Exception {
        if (turns == null || turns.isEmpty()) {
            throw new IllegalArgumentException("scenario turns are required");
        }
        Objects.requireNonNull(handler, "turn handler is required");
        if (endTurn < 1 || endTurn > MAX_DIAGNOSTIC_TURN || endTurn > turns.size()) {
            throw new IllegalArgumentException("diagnostic end turn must be between 1 and 32 and within the scenario");
        }
        for (var index = 0; index < endTurn; index++) {
            var turn = turns.get(index);
            if (turn == null || turn.number() != index + 1) {
                throw new IllegalArgumentException("diagnostic scenario prefix must have contiguous turn numbers");
            }
        }
        for (var index = 0; index < endTurn; index++) {
            handler.process(turns.get(index));
        }
    }

    @FunctionalInterface
    interface TurnHandler {
        void process(NaturalBuyerConversationScenario.Turn turn) throws Exception;
    }
}
