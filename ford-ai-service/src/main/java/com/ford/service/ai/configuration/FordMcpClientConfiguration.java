package com.ford.service.ai.configuration;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpRequest;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ford.ai.mcp", name = "enabled", havingValue = "true")
public class FordMcpClientConfiguration {

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> fordMcpBearerTokenCustomizer(
            McpToolProperties properties) {
        var token = properties.requiredBearerToken();
        properties.requiredTenantId();
        properties.requiredServerUrl();
        return (name, builder) -> {
            if ("ford-catalog".equals(name)) {
                builder.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token));
            }
        };
    }
}
