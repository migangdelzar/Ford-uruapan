package com.ford.service.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityDiagnosticsTest {

    @Test
    void summarizesCapabilitiesWithoutSecrets() {
        var diagnostics = new CapabilityDiagnostics(true, false, "openai",
                true, false, false, false, false);

        var summary = diagnostics.summary();

        assertThat(summary).contains("humanHandoff=enabled");
        assertThat(summary).contains("ai.cloud=enabled");
        assertThat(summary).contains("ai.local=disabled");
        assertThat(summary).contains("meta.outbound=disabled");
        assertThat(summary).contains("outbox=disabled");
        assertThat(summary).contains("observability.traces=disabled");
        assertThat(summary).doesNotContain("private-model", "token=secret");
    }
}
