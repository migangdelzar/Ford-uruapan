package com.ford.service.conversations.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ford.memory")
public class MemoryProperties {

    private boolean enabled = true;
    private int recentMessageLimit = 20;
    private int summaryMaxChars = 4000;
    private int factLimit = 12;
    private int factMaxChars = 500;
    private boolean semanticEnabled;
    private int semanticLimit = 5;
    private boolean cacheEnabled;

    public boolean enabled() { return enabled; }
    public int recentMessageLimit() { return recentMessageLimit; }
    public int summaryMaxChars() { return summaryMaxChars; }
    public int factLimit() { return factLimit; }
    public int factMaxChars() { return factMaxChars; }
    public boolean semanticEnabled() { return semanticEnabled; }
    public int semanticLimit() { return semanticLimit; }
    public boolean cacheEnabled() { return cacheEnabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setRecentMessageLimit(int value) { recentMessageLimit = positive(value, "recentMessageLimit"); }
    public void setSummaryMaxChars(int value) { summaryMaxChars = positive(value, "summaryMaxChars"); }
    public void setFactLimit(int value) { factLimit = positive(value, "factLimit"); }
    public void setFactMaxChars(int value) { factMaxChars = positive(value, "factMaxChars"); }
    public void setSemanticEnabled(boolean value) { semanticEnabled = value; }
    public void setSemanticLimit(int value) { semanticLimit = positive(value, "semanticLimit"); }
    public void setCacheEnabled(boolean value) { cacheEnabled = value; }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
