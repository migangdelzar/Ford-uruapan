package com.ford.service.ai.configuration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpPropertiesTest {

    @Test
    void rejectsEnabledMcpWithoutUrlOrToken() {
        assertThatThrownBy(() -> McpProperties.validate(true, "", "/mcp", "",
                Duration.ofSeconds(30), Set.of("search_vehicles")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsDisabledMcpWithoutCredentials() {
        assertThatCode(() -> McpProperties.validate(false, "", "/mcp", "",
                Duration.ofSeconds(30), Set.of("search_vehicles")))
                .doesNotThrowAnyException();
    }
}
