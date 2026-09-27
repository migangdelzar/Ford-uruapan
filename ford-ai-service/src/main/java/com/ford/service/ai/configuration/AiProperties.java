package com.ford.service.ai.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;

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
    private boolean mediaEnabled;
    private List<String> mediaApprovedProviders = List.of();
    private List<String> mediaImageProviders = List.of();
    private List<String> mediaAudioProviders = List.of();
    @Min(1)
    @Max(10485760)
    private long mediaMaxBytes = 10 * 1024 * 1024;
    @NotNull
    private Duration mediaTimeout = Duration.ofSeconds(8);

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

    public boolean mediaEnabled() { return mediaEnabled; }
    public void setMediaEnabled(boolean value) { mediaEnabled = value; }
    public List<String> mediaApprovedProviders() { return mediaApprovedProviders; }
    public void setMediaApprovedProviders(List<String> value) { mediaApprovedProviders = safeList(value); }
    public List<String> mediaImageProviders() { return mediaImageProviders; }
    public void setMediaImageProviders(List<String> value) { mediaImageProviders = safeList(value); }
    public List<String> mediaAudioProviders() { return mediaAudioProviders; }
    public void setMediaAudioProviders(List<String> value) { mediaAudioProviders = safeList(value); }

    public boolean allowsMediaProvider(String providerId, boolean image, boolean audio) {
        if (!mediaEnabled || providerId == null || !mediaApprovedProviders.contains(providerId)) return false;
        return (!image || mediaImageProviders.contains(providerId))
                && (!audio || mediaAudioProviders.contains(providerId));
    }

    public long mediaMaxBytes() { return mediaMaxBytes; }
    public void setMediaMaxBytes(long value) {
        if (value < 1 || value > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("mediaMaxBytes must be between 1 and 10485760");
        }
        mediaMaxBytes = value;
    }
    public Duration mediaTimeout() { return mediaTimeout; }
    public void setMediaTimeout(Duration value) { mediaTimeout = value; }

    @AssertTrue(message = "ford.ai.media-timeout must be positive and no greater than 8 seconds")
    public boolean isMediaTimeoutValid() {
        return mediaTimeout == null || (!mediaTimeout.isNegative() && !mediaTimeout.isZero()
                && mediaTimeout.compareTo(Duration.ofSeconds(8)) <= 0);
    }

    private static List<String> safeList(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
