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
        assertThat(policy.fallbackText())
                .isEqualTo("No pude generar una respuesta en este momento. Intente de nuevo en unos segundos.");
    }

    @Test
    void enabledPolicyPreservesHandoffBehavior() {
        var policy = new HumanHandoffPolicy(true);

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.shouldCreateHandoff(true)).isTrue();
        assertThat(policy.fallbackText())
                .isEqualTo("No pude generar una respuesta en este momento. Intente de nuevo en unos segundos.")
                .doesNotContain("asesor humano");
    }

    @Test
    void usesTenantRuntimeHandoffSetting() {
        var tenant = java.util.UUID.randomUUID();
        var policy = new HumanHandoffPolicy(false, requestedTenant -> tenant.equals(requestedTenant));

        assertThat(policy.enabled(tenant)).isTrue();
        assertThat(policy.shouldCreateHandoff(tenant, true)).isTrue();
        assertThat(policy.enabled(java.util.UUID.randomUUID())).isFalse();
    }
}
