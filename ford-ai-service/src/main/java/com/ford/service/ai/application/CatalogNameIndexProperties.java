package com.ford.service.ai.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ford.ai.catalog-name-index")
public class CatalogNameIndexProperties {
    private boolean enabled = true;
    private Duration refreshInterval = Duration.ofMinutes(15);
    private int maximumEstimatedTokens = 2048;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getRefreshInterval() { return refreshInterval; }
    public void setRefreshInterval(Duration refreshInterval) {
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("refresh interval must be positive");
        }
        this.refreshInterval = refreshInterval;
    }

    public int getMaximumEstimatedTokens() { return maximumEstimatedTokens; }
    public void setMaximumEstimatedTokens(int maximumEstimatedTokens) {
        if (maximumEstimatedTokens < 1) {
            throw new IllegalArgumentException("maximum estimated tokens must be positive");
        }
        this.maximumEstimatedTokens = maximumEstimatedTokens;
    }
}
