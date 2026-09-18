package com.ford.service.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    void redactsContactFinancialAndProviderIdentifiers() {
        var result = new DefaultPiiRedactor().redact(
                "Cliente maria@example.com teléfono +52 452 123 4567 ingreso $35,000 id=wamid.abc123");

        assertThat(result.value()).doesNotContain("maria@example.com", "452 123 4567", "35,000", "wamid.abc123");
        assertThat(result.value()).contains("[REDACTED]");
    }
}
