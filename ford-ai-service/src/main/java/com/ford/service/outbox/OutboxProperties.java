package com.ford.service.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.outbox")
public class OutboxProperties {

    private boolean enabled;
    private long fixedDelayMs = 1000;
    private long recoveryDelayMs = 5000;
    private int maxAttempts = 5;
    private long leaseSeconds = 30;
    private long backoffBaseSeconds = 2;
    private long backoffMaxSeconds = 300;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public long fixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long value) {
        fixedDelayMs = value;
    }

    public long recoveryDelayMs() {
        return recoveryDelayMs;
    }

    public void setRecoveryDelayMs(long value) {
        recoveryDelayMs = value;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int value) {
        maxAttempts = value;
    }

    public long leaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long value) {
        leaseSeconds = value;
    }

    public long backoffBaseSeconds() {
        return backoffBaseSeconds;
    }

    public void setBackoffBaseSeconds(long value) {
        backoffBaseSeconds = value;
    }

    public long backoffMaxSeconds() {
        return backoffMaxSeconds;
    }

    public void setBackoffMaxSeconds(long value) {
        backoffMaxSeconds = value;
    }
}
