package com.ford.service.ai.configuration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ford.ai.classification")
public class ClassificationProperties {

    private boolean enabled = true;
    @NotBlank
    @Pattern(regexp = "(?i)active|shadow|disabled")
    private String mode = "active";
    @NotBlank
    @Pattern(regexp = "(?i)typesafe|rules")
    private String provider = "typesafe";
    @Min(1)
    private int timeoutMs = 1500;
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minConfidence = 0.70;
    @NotBlank
    @Pattern(regexp = "(?i)rules")
    private String fallback = "rules";
    @NotBlank
    @Pattern(regexp = "(?i)redact|block|allow")
    private String piiPolicy = "allow";
    private boolean intentEnabled = true;
    private boolean leadEnabled = true;
    private boolean handoffEnabled = true;
    private boolean toolsEnabled = true;
    private boolean sensitiveDataEnabled;
    private boolean guardrailsEnabled;
    private boolean selfRefineEnabled;
    @Min(1)
    private int selfRefineMaxAttempts = 1;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String mode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String provider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int timeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public double minConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public String fallback() { return fallback; }
    public void setFallback(String fallback) { this.fallback = fallback; }
    public String piiPolicy() { return piiPolicy; }
    public void setPiiPolicy(String piiPolicy) { this.piiPolicy = piiPolicy; }
    public boolean intentEnabled() { return intentEnabled; }
    public void setIntentEnabled(boolean intentEnabled) { this.intentEnabled = intentEnabled; }
    public boolean leadEnabled() { return leadEnabled; }
    public void setLeadEnabled(boolean leadEnabled) { this.leadEnabled = leadEnabled; }
    public boolean handoffEnabled() { return handoffEnabled; }
    public void setHandoffEnabled(boolean handoffEnabled) { this.handoffEnabled = handoffEnabled; }
    public boolean toolsEnabled() { return toolsEnabled; }
    public void setToolsEnabled(boolean toolsEnabled) { this.toolsEnabled = toolsEnabled; }
    public boolean sensitiveDataEnabled() { return sensitiveDataEnabled; }
    public void setSensitiveDataEnabled(boolean sensitiveDataEnabled) { this.sensitiveDataEnabled = sensitiveDataEnabled; }
    public boolean guardrailsEnabled() { return guardrailsEnabled; }
    public void setGuardrailsEnabled(boolean guardrailsEnabled) { this.guardrailsEnabled = guardrailsEnabled; }
    public boolean selfRefineEnabled() { return selfRefineEnabled; }
    public void setSelfRefineEnabled(boolean selfRefineEnabled) { this.selfRefineEnabled = selfRefineEnabled; }
    public int selfRefineMaxAttempts() { return selfRefineMaxAttempts; }
    public void setSelfRefineMaxAttempts(int selfRefineMaxAttempts) { this.selfRefineMaxAttempts = selfRefineMaxAttempts; }

    public boolean allClassifiersEnabled() {
        return intentEnabled && leadEnabled && handoffEnabled && toolsEnabled && sensitiveDataEnabled;
    }
}
