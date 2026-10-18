package com.ford.service.ai.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ford.knowledge.rag")
public class KnowledgeRagProperties {

    private boolean rerankerEnabled;
    @Min(1)
    @Max(8)
    private int rerankerTopK = 5;

    public boolean rerankerEnabled() {
        return rerankerEnabled;
    }

    public void setRerankerEnabled(boolean rerankerEnabled) {
        this.rerankerEnabled = rerankerEnabled;
    }

    public int rerankerTopK() {
        return rerankerTopK;
    }

    public void setRerankerTopK(int rerankerTopK) {
        this.rerankerTopK = rerankerTopK;
    }
}
