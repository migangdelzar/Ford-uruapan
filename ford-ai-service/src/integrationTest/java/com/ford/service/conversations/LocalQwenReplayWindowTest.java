package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LocalQwenReplayWindowTest {

    @Test
    void processesOnlyRequestedPrefixInScenarioOrder() throws Exception {
        var seen = new ArrayList<Integer>();

        LocalQwenReplayWindow.run(turns(4), 3, turn -> seen.add(turn.number()));

        assertThat(seen).containsExactly(1, 2, 3);
    }

    @Test
    void stopsAtFirstHandlerFailureAndPropagatesSameException() {
        var seen = new ArrayList<Integer>();
        var failure = new IllegalStateException("synthetic failure");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> LocalQwenReplayWindow.run(turns(4), 4, turn -> {
                    seen.add(turn.number());
                    if (turn.number() == 2) throw failure;
                }))
                .isSameAs(failure);

        assertThat(seen).containsExactly(1, 2);
    }

    @Test
    void rejectsBoundsOutsideOneThroughThirtyTwoBeforeCallingHandler() {
        var called = new ArrayList<Integer>();

        for (var invalid : List.of(0, 33)) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    LocalQwenReplayWindow.run(turns(4), invalid, turn -> called.add(turn.number())));
        }

        assertThat(called).isEmpty();
    }

    @Test
    void rejectsNoncontiguousScenarioBeforeCallingHandler() {
        var called = new ArrayList<Integer>();
        var turns = List.of(turn(1), turn(3));

        assertThatIllegalArgumentException().isThrownBy(() ->
                LocalQwenReplayWindow.run(turns, 2, turn -> called.add(turn.number())))
                .withMessageContaining("contiguous");

        assertThat(called).isEmpty();
    }

    private static List<NaturalBuyerConversationScenario.Turn> turns(int count) {
        var turns = new ArrayList<NaturalBuyerConversationScenario.Turn>();
        for (var number = 1; number <= count; number++) turns.add(turn(number));
        return List.copyOf(turns);
    }

    private static NaturalBuyerConversationScenario.Turn turn(int number) {
        return new NaturalBuyerConversationScenario.Turn(number, "Synthetic buyer turn " + number,
                NaturalBuyerConversationScenario.Expectation.DISCOVERY);
    }
}
