package com.ford.service.channels.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ford.channels.debounce")
public class InboundDebounceProperties {

    private boolean enabled;
    private Duration window = Duration.ofSeconds(5);
    private int claimBatchSize = 50;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public Duration window() {
        return window;
    }

    public void setWindow(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("debounce window must be positive");
        }
        if (value.compareTo(Duration.ofSeconds(6)) > 0) {
            throw new IllegalArgumentException("debounce window must not exceed six seconds");
        }
        window = value;
    }

    public int claimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int value) {
        if (value < 1 || value > 500) {
            throw new IllegalArgumentException("claimBatchSize must be between 1 and 500");
        }
        claimBatchSize = value;
    }
}
