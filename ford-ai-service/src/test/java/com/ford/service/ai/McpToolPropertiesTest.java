package com.ford.service.ai;

import com.ford.service.ai.configuration.McpToolProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolPropertiesTest {

    @Test
    void staysDisabledWithoutEndpointCredentials() {
        var properties = new McpToolProperties();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.requiredTenantId()).isNull();
        assertThat(properties.requiredBearerToken()).isEmpty();
    }

    @Test
    void requiresAnExplicitTenantAndBearerTokenWhenEnabled() {
        var properties = new McpToolProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::requiredTenantId)
                .hasMessageContaining("FORD_MCP_TENANT_ID");

        properties.setTenantId(UUID.randomUUID().toString());
        assertThatThrownBy(properties::requiredBearerToken)
                .hasMessageContaining("MCP_HTTP_BEARER_TOKEN");
    }

    @Test
    void parsesOnlyUuidTenantScope() {
        var tenantId = UUID.randomUUID();
        var properties = new McpToolProperties();
        properties.setEnabled(true);
        properties.setTenantId(tenantId.toString());
        properties.setBearerToken("secret-test-value");
        properties.setServerUrl("https://mcp.forduruapan.mx");

        assertThat(properties.requiredTenantId()).isEqualTo(tenantId);
        assertThat(properties.requiredBearerToken()).isEqualTo("secret-test-value");
        assertThat(properties.requiredServerUrl()).isEqualTo("https://mcp.forduruapan.mx");
    }

    @Test
    void refusesInsecureRemoteMcpEndpoints() {
        var properties = new McpToolProperties();
        properties.setEnabled(true);
        properties.setServerUrl("http://mcp.forduruapan.mx");

        assertThatThrownBy(properties::requiredServerUrl)
                .hasMessageContaining("HTTPS");
    }
}
