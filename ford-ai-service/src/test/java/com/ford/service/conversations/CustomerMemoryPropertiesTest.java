package com.ford.service.conversations;

import com.ford.service.conversations.application.CustomerMemoryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerMemoryPropertiesTest {

    @Test
    void sharedProfileIsDisabledByDefault() {
        assertThat(new CustomerMemoryProperties().enabled()).isFalse();
    }

    @Test
    void disabledFeatureAllowsEmptyOptionalPreviousKeyEnvironmentValue() {
        var properties = new CustomerMemoryProperties();
        properties.setPreviousHmacKey("");

        properties.validate();
    }

    @Test
    void enablingRequiresStrongCurrentKeyAndVersion() {
        var properties = new CustomerMemoryProperties();
        properties.setEnabled(true);
        properties.setHmacKey("short");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);

        properties.setHmacKey("01234567890123456789012345678901");
        properties.setHmacKeyVersion(1);
        properties.validate();
    }

    @Test
    void previousKeyRequiresVersionAndStrongKey() {
        var properties = new CustomerMemoryProperties();
        properties.setPreviousHmacKey("abcdefghijklmnopqrstuvwxyzABCDEF");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class);

        properties.setPreviousHmacKeyVersion(1);
        properties.setHmacKey("01234567890123456789012345678901");
        properties.setHmacKeyVersion(2);
        properties.setEnabled(true);
        properties.validate();
    }

    @Test
    void retentionAndContextBoundsAreFixedToApprovedLimits() {
        var properties = new CustomerMemoryProperties();
        assertThat(properties.activeFactDays()).isEqualTo(90);
        assertThat(properties.contextFactLimit()).isEqualTo(3);
        assertThat(properties.contextMaxChars()).isEqualTo(600);
    }

    @Test
    void acceptsBase64EncodedRandomKeyBytes() {
        var properties = new CustomerMemoryProperties();
        properties.setEnabled(true);
        properties.setHmacKey(java.util.Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        properties.setHmacKeyVersion(1);
        properties.validate();
        assertThat(properties.hmacKeyBytes()).hasSize(32);
    }
}
