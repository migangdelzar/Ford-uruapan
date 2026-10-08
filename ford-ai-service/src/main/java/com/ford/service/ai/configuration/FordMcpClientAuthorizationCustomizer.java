package com.ford.service.ai.configuration;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;

import java.util.Objects;

/** Adds MCP and Cloudflare Access credentials to the Ford MCP connection only. */
public final class FordMcpClientAuthorizationCustomizer
        implements McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> {

    public static final String FORD_CONNECTION_NAME = "ford-uruapan";

    private final McpProperties properties;

    public FordMcpClientAuthorizationCustomizer(McpProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void customize(String connectionName, HttpClientStreamableHttpTransport.Builder builder) {
        if (!properties.enabled() || !FORD_CONNECTION_NAME.equals(connectionName)
                || properties.token() == null || properties.token().isBlank()) {
            return;
        }
        builder.httpRequestCustomizer((request, method, uri, body, context) -> {
            request.setHeader("Authorization", "Bearer " + properties.token());
            if (properties.accessClientId() != null && !properties.accessClientId().isBlank()
                    && properties.accessClientSecret() != null && !properties.accessClientSecret().isBlank()) {
                request.setHeader("CF-Access-Client-Id", properties.accessClientId());
                request.setHeader("CF-Access-Client-Secret", properties.accessClientSecret());
            }
        });
    }
}
