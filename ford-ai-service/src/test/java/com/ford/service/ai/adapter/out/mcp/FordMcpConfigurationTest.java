package com.ford.service.ai.adapter.out.mcp;

import com.ford.service.ai.configuration.McpProperties;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;

import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class FordMcpConfigurationTest {

    @Test
    void addsBearerTokenOnlyToTheFordConnection() {
        var properties = new McpProperties();
        properties.setEnabled(true);
        properties.setToken("test-token");
        var customizer = FordMcpConfiguration.mcpAuthentication(properties);
        var builder = mock(HttpClientStreamableHttpTransport.Builder.class);

        customizer.customize("ford-uruapan", builder);

        var requestCustomizer = ArgumentCaptor.forClass(McpSyncHttpClientRequestCustomizer.class);
        verify(builder).httpRequestCustomizer(requestCustomizer.capture());
        var request = HttpRequest.newBuilder(java.net.URI.create("http://localhost/mcp"));
        requestCustomizer.getValue().customize(request, "POST", java.net.URI.create("http://localhost/mcp"),
                "{}", null);

        assertThat(request.build().headers().firstValue("Authorization"))
                .hasValue("Bearer test-token");
    }

    @Test
    void doesNotCustomizeOtherConnections() {
        var properties = new McpProperties();
        properties.setEnabled(true);
        properties.setToken("test-token");
        McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> customizer =
                FordMcpConfiguration.mcpAuthentication(properties);
        var builder = mock(HttpClientStreamableHttpTransport.Builder.class);

        customizer.customize("other", builder);

        org.mockito.Mockito.verifyNoInteractions(builder);
    }
}
