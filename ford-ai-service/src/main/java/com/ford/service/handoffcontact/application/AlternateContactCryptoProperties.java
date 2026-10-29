package com.ford.service.handoffcontact.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ford.alternate-contact.crypto")
public class AlternateContactCryptoProperties {
    private String currentKeyBase64;
    private int currentKeyVersion = 1;
    private String previousKeyBase64;
    private int previousKeyVersion;

    public AlternateContactCryptoProperties() { }

    public AlternateContactCryptoProperties(String currentKeyBase64, int currentKeyVersion,
                                            String previousKeyBase64, int previousKeyVersion) {
        this.currentKeyBase64 = currentKeyBase64;
        this.currentKeyVersion = currentKeyVersion;
        this.previousKeyBase64 = previousKeyBase64;
        this.previousKeyVersion = previousKeyVersion;
    }

    public String getCurrentKeyBase64() { return currentKeyBase64; }
    public void setCurrentKeyBase64(String value) { currentKeyBase64 = value; }
    public int getCurrentKeyVersion() { return currentKeyVersion; }
    public void setCurrentKeyVersion(int value) { currentKeyVersion = value; }
    public String getPreviousKeyBase64() { return previousKeyBase64; }
    public void setPreviousKeyBase64(String value) { previousKeyBase64 = value; }
    public int getPreviousKeyVersion() { return previousKeyVersion; }
    public void setPreviousKeyVersion(int value) { previousKeyVersion = value; }
}
