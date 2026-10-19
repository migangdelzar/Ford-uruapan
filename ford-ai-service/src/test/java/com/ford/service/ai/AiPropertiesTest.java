package com.ford.service.ai;

import com.ford.service.ai.configuration.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesTest {

    @Test
    void responseVerificationDefaultsOnAndCanBeDisabledForOperations() {
        var properties = new AiProperties();

        assertThat(properties.responseVerificationEnabled()).isTrue();

        properties.setResponseVerificationEnabled(false);

        assertThat(properties.responseVerificationEnabled()).isFalse();
    }
}
