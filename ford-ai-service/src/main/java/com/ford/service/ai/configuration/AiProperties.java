package com.ford.service.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.ai")
public class AiProperties {

    private String chatProvider = "ollama";
    private String cloudChatProvider = "openai";
    private String localChatProvider = "ollama";
    private String cloudChatModelVersion = "configured-cloud-model";
    private String localChatModelVersion = "configured-local-model";
    private String embeddingProvider = "ollama";
    private String embeddingModelVersion = "configured-embedding-model";
    private boolean cloudEnabled = true;
    private boolean localEnabled = true;

    public String chatProvider() {
        return chatProvider;
    }

    public void setChatProvider(String value) {
        chatProvider = value;
    }

    public String cloudChatProvider() {
        return cloudChatProvider;
    }

    public void setCloudChatProvider(String value) {
        cloudChatProvider = value;
    }

    public String localChatProvider() {
        return localChatProvider;
    }

    public void setLocalChatProvider(String value) {
        localChatProvider = value;
    }

    public String cloudChatModelVersion() {
        return cloudChatModelVersion;
    }

    public void setCloudChatModelVersion(String value) {
        cloudChatModelVersion = value;
    }

    public String localChatModelVersion() {
        return localChatModelVersion;
    }

    public void setLocalChatModelVersion(String value) {
        localChatModelVersion = value;
    }

    public String embeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String value) {
        embeddingProvider = value;
    }

    public String embeddingModelVersion() {
        return embeddingModelVersion;
    }

    public void setEmbeddingModelVersion(String value) {
        embeddingModelVersion = value;
    }

    public boolean cloudEnabled() {
        return cloudEnabled;
    }

    public void setCloudEnabled(boolean value) {
        cloudEnabled = value;
    }

    public boolean localEnabled() {
        return localEnabled;
    }

    public void setLocalEnabled(boolean value) {
        localEnabled = value;
    }
}
