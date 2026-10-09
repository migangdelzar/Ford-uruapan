package com.ford.service.conversations.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ford.memory")
public class MemoryProperties {

    private boolean enabled = true;
    private int recentMessageLimit = 20;
    private int promptRecentMessageLimit = 20;
    private int summaryMaxChars = 4000;
    private int factLimit = 12;
    private int factMaxChars = 500;
    private boolean semanticEnabled;
    private int semanticLimit = 5;
    private boolean semanticRecallEnabled;
    private int semanticRecallLimit = 3;
    private double semanticRecallMinimumSimilarity = 0.78;
    private String semanticEmbeddingModel = "nomic-embed-text";
    private boolean cacheEnabled;
    private Duration cacheTtl = Duration.ofHours(24);
    private Duration workingMemoryTtl = Duration.ofDays(90);

    public boolean enabled() { return enabled; }
    public int recentMessageLimit() { return recentMessageLimit; }
    public int promptRecentMessageLimit() { return promptRecentMessageLimit; }
    public int summaryMaxChars() { return summaryMaxChars; }
    public int factLimit() { return factLimit; }
    public int factMaxChars() { return factMaxChars; }
    public boolean semanticEnabled() { return semanticEnabled; }
    public int semanticLimit() { return semanticLimit; }
    public boolean semanticRecallEnabled() { return semanticRecallEnabled; }
    public int semanticRecallLimit() { return semanticRecallLimit; }
    public double semanticRecallMinimumSimilarity() { return semanticRecallMinimumSimilarity; }
    public String semanticEmbeddingModel() { return semanticEmbeddingModel; }
    public boolean cacheEnabled() { return cacheEnabled; }
    public Duration cacheTtl() { return cacheTtl; }
    public Duration workingMemoryTtl() { return workingMemoryTtl; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setRecentMessageLimit(int value) {
        recentMessageLimit = positive(value, "recentMessageLimit");
        promptRecentMessageLimit = Math.min(promptRecentMessageLimit, recentMessageLimit);
    }
    public void setPromptRecentMessageLimit(int value) {
        promptRecentMessageLimit = positive(value, "promptRecentMessageLimit");
        if (promptRecentMessageLimit > recentMessageLimit) {
            throw new IllegalArgumentException("promptRecentMessageLimit cannot exceed recentMessageLimit");
        }
    }
    public void setSummaryMaxChars(int value) { summaryMaxChars = positive(value, "summaryMaxChars"); }
    public void setFactLimit(int value) { factLimit = positive(value, "factLimit"); }
    public void setFactMaxChars(int value) { factMaxChars = positive(value, "factMaxChars"); }
    public void setSemanticEnabled(boolean value) { semanticEnabled = value; }
    public void setSemanticLimit(int value) { semanticLimit = positive(value, "semanticLimit"); }
    public void setSemanticRecallEnabled(boolean value) { semanticRecallEnabled = value; }
    public void setSemanticRecallLimit(int value) {
        semanticRecallLimit = Math.min(3, positive(value, "semanticRecallLimit"));
    }
    public void setSemanticRecallMinimumSimilarity(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("semanticRecallMinimumSimilarity must be between 0 and 1");
        }
        semanticRecallMinimumSimilarity = value;
    }
    public void setSemanticEmbeddingModel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("semanticEmbeddingModel must not be blank");
        }
        semanticEmbeddingModel = value;
    }
    public void setCacheEnabled(boolean value) { cacheEnabled = value; }
    public void setCacheTtl(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
        cacheTtl = value;
    }
    public void setWorkingMemoryTtl(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("workingMemoryTtl must be positive");
        }
        workingMemoryTtl = value;
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
