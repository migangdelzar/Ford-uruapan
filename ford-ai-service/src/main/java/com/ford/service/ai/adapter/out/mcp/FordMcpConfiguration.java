package com.ford.service.ai.adapter.out.mcp;

import com.ford.service.ai.configuration.McpProperties;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ford.mcp", name = "enabled", havingValue = "true")
public class FordMcpConfiguration {

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> fordMcpAuthentication(McpProperties properties) {
        return mcpAuthentication(properties);
    }

    public static McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpAuthentication(
            McpProperties properties) {
        return (connectionName, builder) -> {
            if (!"ford-uruapan".equals(connectionName)) {
                return;
            }
            builder.httpRequestCustomizer((request, method, uri, body, transportContext) ->
                    request.header("Authorization", "Bearer " + properties.token()));
        };
    }
}
