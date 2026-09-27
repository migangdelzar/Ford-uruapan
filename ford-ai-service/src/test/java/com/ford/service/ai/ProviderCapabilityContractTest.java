package com.ford.service.ai;

import com.ford.service.ai.application.ProviderCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCapabilityContractTest {

    @Test
    void localAndCloudCapabilitiesAreExplicit() {
        var capabilities = new ProviderCapabilities(true, true, true);

        assertThat(capabilities.toolCalling()).isTrue();
        assertThat(capabilities.structuredOutput()).isTrue();
        assertThat(capabilities.embeddings()).isTrue();
        assertThat(capabilities.imageInput()).isFalse();
        assertThat(capabilities.audioInput()).isFalse();
    }
}
