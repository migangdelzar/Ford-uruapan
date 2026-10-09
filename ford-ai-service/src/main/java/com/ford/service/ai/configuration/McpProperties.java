package com.ford.service.ai.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "ford.mcp")
public class McpProperties {

    private static final Set<String> DEFAULT_READ_ONLY_TOOLS = Set.of(
            "search_vehicles", "search_vehicle_and_offers", "get_vehicle", "compare_vehicles", "search_offers",
            "compare_trims", "compare_model_years", "get_variant_comparisons", "get_page_links",
            "list_promotions", "get_vehicle_provenance", "get_catalog_status");

    private boolean enabled;
    private String url = "http://127.0.0.1:3001";
    private String endpoint = "/mcp";
    private String token = "";
    private String accessClientId = "";
    private String accessClientSecret = "";
    private Duration requestTimeout = Duration.ofSeconds(30);
    private boolean requiredForVolatileInformation = true;
    private Set<String> readOnlyToolNames = DEFAULT_READ_ONLY_TOOLS;

    @PostConstruct
    void validateConfiguration() {
        validate(enabled, url, endpoint, token, requestTimeout, readOnlyToolNames);
    }

    public static void validate(boolean enabled, String url, String endpoint, String token,
                                Duration requestTimeout, Set<String> readOnlyToolNames) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP request timeout must be positive");
        }
        if (readOnlyToolNames == null || readOnlyToolNames.isEmpty()) {
            throw new IllegalArgumentException("MCP read-only tool names are required");
        }
        if (!enabled) {
            return;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MCP URL is required when MCP is enabled");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("MCP token is required when MCP is enabled");
        }
        if (endpoint == null || endpoint.isBlank() || !endpoint.trim().startsWith("/")) {
            throw new IllegalArgumentException("MCP endpoint must start with '/'");
        }
    }

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String url() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String endpoint() { return endpoint; }
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null || endpoint.isBlank()
                ? endpoint : (endpoint.trim().startsWith("/") ? endpoint.trim() : "/" + endpoint.trim());
    }
    public String token() { return token; }
    public void setToken(String token) { this.token = token; }
    public String accessClientId() { return accessClientId; }
    public void setAccessClientId(String accessClientId) { this.accessClientId = accessClientId; }
    public String accessClientSecret() { return accessClientSecret; }
    public void setAccessClientSecret(String accessClientSecret) { this.accessClientSecret = accessClientSecret; }
    public Duration requestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public boolean requiredForVolatileInformation() { return requiredForVolatileInformation; }
    public void setRequiredForVolatileInformation(boolean value) { this.requiredForVolatileInformation = value; }
    public Set<String> readOnlyToolNames() { return Set.copyOf(readOnlyToolNames); }
    public void setReadOnlyToolNames(Set<String> names) {
        this.readOnlyToolNames = names == null ? Set.of() : names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
