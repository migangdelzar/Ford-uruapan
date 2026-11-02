package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalReplayNoHandoffAssertionsTest {
    @Test
    void acceptsOnlyZeroHandoffTurns() {
        assertThatCode(() -> LocalReplayNoHandoffAssertions.assertNoHandoff(0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LocalReplayNoHandoffAssertions.assertNoHandoff(1))
                .isInstanceOf(AssertionError.class).hasMessageContaining("detected 1 handoff");
    }
}
