package com.ford.service.ai;

import com.ford.service.ai.application.FordCatalogSourceReferencePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FordCatalogSourceReferencePolicyTest {

    private final FordCatalogSourceReferencePolicy policy = new FordCatalogSourceReferencePolicy();

    @Test
    void allowsFordHostsAndStripsTrackingDataCredentialsAndPort() {
        assertThat(policy.safeReference("https://www.forduruapan.mx/RANGER/27/?token=hidden#price"))
                .contains("https://www.forduruapan.mx/RANGER/27/");
        assertThat(policy.safeReference("https://ford.mx/model?campaign=x"))
                .contains("https://ford.mx/model");
        assertThat(policy.safeReference("https://user:secret@catalog.ford.mx:8443/model?token=secret#price"))
                .contains("https://catalog.ford.mx/model");
    }

    @Test
    void rejectsNonHttpsUntrustedAndMalformedUrls() {
        assertThat(policy.safeReference("http://ford.mx/model")).isEmpty();
        assertThat(policy.safeReference("https://ford.mx.attacker.example/model")).isEmpty();
        assertThat(policy.safeReference("https://notforduruapan.mx/model")).isEmpty();
        assertThat(policy.safeReference("https://example.com/ford.mx/model")).isEmpty();
        assertThat(policy.safeReference("not a url")).isEmpty();
        assertThat(policy.safeReference(" ")).isEmpty();
        assertThat(policy.safeReference(null)).isEmpty();
    }
}
