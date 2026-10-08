package com.ford.service.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.ai.ollama-access")
public class OllamaAccessProperties {

    private String clientId = "";
    private String clientSecret = "";

    public String clientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String clientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
}
