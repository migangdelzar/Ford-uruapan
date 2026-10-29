package com.ford.service.conversations.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.customer-memory")
public class CustomerMemoryProperties {

    private boolean enabled;
    private String hmacKey;
    private Integer hmacKeyVersion;
    private String previousHmacKey;
    private Integer previousHmacKeyVersion;
    private final int activeFactDays = 90;
    private final int contextFactLimit = 3;
    private final int contextMaxChars = 600;
    private byte[] decodedCurrentKey;
    private byte[] decodedPreviousKey;

    public CustomerMemoryProperties() { }

    public CustomerMemoryProperties(String hmacKey, Integer hmacKeyVersion,
                                    String previousHmacKey, Integer previousHmacKeyVersion) {
        this.hmacKey = hmacKey;
        this.hmacKeyVersion = hmacKeyVersion;
        this.previousHmacKey = previousHmacKey;
        this.previousHmacKeyVersion = previousHmacKeyVersion;
    }

    public boolean enabled() { return enabled; }
    public String hmacKey() { return hmacKey; }
    public Integer hmacKeyVersion() { return hmacKeyVersion; }
    public String previousHmacKey() { return previousHmacKey; }
    public Integer previousHmacKeyVersion() { return previousHmacKeyVersion; }
    public int activeFactDays() { return activeFactDays; }
    public int contextFactLimit() { return contextFactLimit; }
    public int contextMaxChars() { return contextMaxChars; }
    public byte[] hmacKeyBytes() {
        if (decodedCurrentKey == null) decodedCurrentKey = decodeKey(hmacKey, "current HMAC key");
        return decodedCurrentKey.clone();
    }
    public byte[] previousHmacKeyBytes() {
        if (decodedPreviousKey == null) decodedPreviousKey = decodeKey(previousHmacKey, "previous HMAC key");
        return decodedPreviousKey.clone();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setHmacKeyVersion(Integer hmacKeyVersion) { this.hmacKeyVersion = hmacKeyVersion; }
    public void setPreviousHmacKey(String previousHmacKey) { this.previousHmacKey = previousHmacKey; this.decodedPreviousKey = null; }
    public void setPreviousHmacKeyVersion(Integer previousHmacKeyVersion) {
        this.previousHmacKeyVersion = previousHmacKeyVersion;
    }
    public void setHmacKey(String hmacKey) {
        this.hmacKey = hmacKey;
        this.decodedCurrentKey = null;
    }
    public void validate() {
        if (enabled) {
            validateKey(hmacKey, "current HMAC key");
            validateVersion(hmacKeyVersion, "current HMAC key version");
        }
        var previousConfigured = (previousHmacKey != null && !previousHmacKey.isBlank())
                || previousHmacKeyVersion != null;
        if (previousConfigured) {
            validateKey(previousHmacKey, "previous HMAC key");
            validateVersion(previousHmacKeyVersion, "previous HMAC key version");
            if (hmacKeyVersion != null && hmacKeyVersion.equals(previousHmacKeyVersion)) {
                throw new IllegalStateException("HMAC key versions must be distinct");
            }
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void validateOnStartup() {
        validate();
    }

    private static void validateKey(String value, String name) {
        if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(name + " must contain at least 32 bytes");
        }
    }

    private static void validateVersion(Integer value, String name) {
        if (value == null || value < 1) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    private static byte[] decodeKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured");
        }
        try {
            var decoded = java.util.Base64.getDecoder().decode(value);
            return decoded.length >= 32 ? decoded : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

}
