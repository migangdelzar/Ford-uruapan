package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpringAiProviderConfiguration.Properties.class)
public class SpringAiProviderConfiguration {

    @Bean
    ModelProviderPolicy modelProviderPolicy(Properties properties) {
        return new DefaultModelProviderPolicy(List.of(
                new ProviderDescriptor(
                        properties.cloudChatProvider(),
                        new ProviderCapabilities(true, true, true),
                        properties.cloudEnabled(),
                        true,
                        properties.cloudChatModelVersion()),
                new ProviderDescriptor(
                        properties.localChatProvider(),
                        new ProviderCapabilities(true, true, true),
                        properties.localEnabled(),
                        false,
                        properties.localChatModelVersion())));
    }

    @ConfigurationProperties(prefix = "ford.ai")
    public static class Properties {
        private String cloudChatProvider = "openai";
        private String localChatProvider = "ollama";
        private String cloudChatModelVersion = "configured-cloud-model";
        private String localChatModelVersion = "configured-local-model";
        private String embeddingProvider = "ollama";
        private String embeddingModelVersion = "configured-embedding-model";
        private boolean cloudEnabled = true;
        private boolean localEnabled = true;

        public String cloudChatProvider() { return cloudChatProvider; }
        public void setCloudChatProvider(String value) { cloudChatProvider = value; }
        public String localChatProvider() { return localChatProvider; }
        public void setLocalChatProvider(String value) { localChatProvider = value; }
        public String cloudChatModelVersion() { return cloudChatModelVersion; }
        public void setCloudChatModelVersion(String value) { cloudChatModelVersion = value; }
        public String localChatModelVersion() { return localChatModelVersion; }
        public void setLocalChatModelVersion(String value) { localChatModelVersion = value; }
        public String embeddingProvider() { return embeddingProvider; }
        public void setEmbeddingProvider(String value) { embeddingProvider = value; }
        public String embeddingModelVersion() { return embeddingModelVersion; }
        public void setEmbeddingModelVersion(String value) { embeddingModelVersion = value; }
        public boolean cloudEnabled() { return cloudEnabled; }
        public void setCloudEnabled(boolean value) { cloudEnabled = value; }
        public boolean localEnabled() { return localEnabled; }
        public void setLocalEnabled(boolean value) { localEnabled = value; }
    }
}
