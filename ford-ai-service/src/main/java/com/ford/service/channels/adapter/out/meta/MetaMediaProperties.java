package com.ford.service.channels.adapter.out.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ford.channels.meta.media")
public class MetaMediaProperties {
    private String graphApiBaseUrl = "https://graph.facebook.com";
    private String graphApiVersion = "";

    public String getGraphApiBaseUrl() { return graphApiBaseUrl; }
    public void setGraphApiBaseUrl(String value) { graphApiBaseUrl = value; }
    public String getGraphApiVersion() { return graphApiVersion; }
    public void setGraphApiVersion(String value) { graphApiVersion = value == null ? "" : value; }

    public boolean isConfigured(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || graphApiBaseUrl == null
                || graphApiVersion == null || !graphApiVersion.matches("v\\d+(\\.\\d+)?")) return false;
        try {
            var uri = java.net.URI.create(graphApiBaseUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "graph.facebook.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
