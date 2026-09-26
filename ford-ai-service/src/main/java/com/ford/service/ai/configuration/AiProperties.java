package com.ford.service.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Validated
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
    private boolean logProviderErrors;
    @Min(1)
    @Max(2)
    private int providerMaxAttempts = 2;
    @NotNull
    private Duration providerMaxRetryAfter = Duration.ofSeconds(3);

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

    public boolean logProviderErrors() {
        return logProviderErrors;
    }

    public void setLogProviderErrors(boolean value) {
        logProviderErrors = value;
    }

    public int providerMaxAttempts() {
        return providerMaxAttempts;
    }

    public void setProviderMaxAttempts(int value) {
        if (value < 1 || value > 2) {
            throw new IllegalArgumentException("providerMaxAttempts must be between 1 and 2");
        }
        providerMaxAttempts = value;
    }

    public Duration providerMaxRetryAfter() {
        return providerMaxRetryAfter;
    }

    public void setProviderMaxRetryAfter(Duration value) {
        providerMaxRetryAfter = value;
    }

    @AssertTrue(message = "ford.ai.provider-max-retry-after must not be negative")
    public boolean isProviderMaxRetryAfterNonNegative() {
        return providerMaxRetryAfter == null || !providerMaxRetryAfter.isNegative();
    }
}
