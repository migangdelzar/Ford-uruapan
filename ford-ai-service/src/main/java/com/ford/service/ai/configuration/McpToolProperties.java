package com.ford.service.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;
import java.net.URI;

@ConfigurationProperties(prefix = "ford.ai.mcp")
public class McpToolProperties {

    private boolean enabled;
    private String tenantId = "";
    private String bearerToken = "";
    private String serverUrl = "";

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String tenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId == null ? "" : tenantId.strip(); }
    public String bearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken == null ? "" : bearerToken; }
    public String serverUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl == null ? "" : serverUrl.strip(); }

    public UUID requiredTenantId() {
        if (!enabled) {
            return null;
        }
        if (tenantId.isBlank()) {
            throw new IllegalStateException("FORD_MCP_TENANT_ID is required when Ford MCP tools are enabled");
        }
        try {
            return UUID.fromString(tenantId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("FORD_MCP_TENANT_ID must be a UUID", exception);
        }
    }

    public String requiredBearerToken() {
        if (!enabled) {
            return "";
        }
        if (bearerToken.isBlank()) {
            throw new IllegalStateException("MCP_HTTP_BEARER_TOKEN is required when Ford MCP tools are enabled");
        }
        return bearerToken;
    }

    public String requiredServerUrl() {
        if (!enabled) {
            return "";
        }
        if (serverUrl.isBlank()) {
            throw new IllegalStateException("FORD_MCP_SERVER_URL is required when Ford MCP tools are enabled");
        }
        try {
            var uri = URI.create(serverUrl);
            var local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !("https".equalsIgnoreCase(uri.getScheme())
                    || (local && "http".equalsIgnoreCase(uri.getScheme())))) {
                throw new IllegalStateException("FORD_MCP_SERVER_URL must use HTTPS except for local development");
            }
            return uri.toString().replaceAll("/$", "");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("FORD_MCP_SERVER_URL is invalid", exception);
        }
    }
}
