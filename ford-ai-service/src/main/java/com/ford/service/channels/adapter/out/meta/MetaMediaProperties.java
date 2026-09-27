package com.ford.service.channels.adapter.out.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ford.channels.meta.media")
public class MetaMediaProperties {
    private String accessToken = "";
    private String graphApiBaseUrl = "https://graph.facebook.com";
    private String graphApiVersion = "";

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String value) { accessToken = value == null ? "" : value; }
    public String getGraphApiBaseUrl() { return graphApiBaseUrl; }
    public void setGraphApiBaseUrl(String value) { graphApiBaseUrl = value; }
    public String getGraphApiVersion() { return graphApiVersion; }
    public void setGraphApiVersion(String value) { graphApiVersion = value == null ? "" : value; }

    public boolean isConfigured() {
        return accessToken != null && !accessToken.isBlank()
                && graphApiBaseUrl != null && !graphApiBaseUrl.isBlank()
                && graphApiVersion != null && !graphApiVersion.isBlank();
    }
}
