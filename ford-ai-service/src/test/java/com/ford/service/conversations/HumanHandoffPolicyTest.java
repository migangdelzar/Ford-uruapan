package com.ford.service.conversations;

import com.ford.service.shared.HumanHandoffPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HumanHandoffPolicyTest {

    @Test
    void disabledPolicyNeverCreatesHandoff() {
        var policy = new HumanHandoffPolicy(false);

        assertThat(policy.enabled()).isFalse();
        assertThat(policy.shouldCreateHandoff(true)).isFalse();
        assertThat(policy.fallbackText()).contains("No pude generar");
    }

    @Test
    void enabledPolicyPreservesHandoffBehavior() {
        var policy = new HumanHandoffPolicy(true);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.shouldCreateHandoff(true)).isTrue();
        assertThat(policy.fallbackText()).contains("asesor humano");
    }
}
