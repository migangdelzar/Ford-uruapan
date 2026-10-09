package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.configuration.ChatProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelProfileConfigurationTest {

    @Test
    void selectsDeepSeekByDefaultAndKeepsOpenRouterAsTheOnlyFallback() {
        var properties = aiProperties("deepseek");
        var policy = providerPolicy(properties);

        assertThat(policy.providers()).extracting(ProviderDescriptor::id)
                .containsExactly("deepseek", "openrouter");
    }

    @Test
    void selectsLocalQwenAsPrimaryWhenTheLocalProfileIsSelected() {
        var properties = aiProperties("local");
        var policy = providerPolicy(properties);

        assertThat(policy.providers()).extracting(ProviderDescriptor::id)
                .containsExactly("ollama", "openrouter");
    }

    private static AiProperties aiProperties(String profile) {
        var properties = new AiProperties();
        properties.setModelProfile(profile);
        properties.setCloudChatProvider("deepseek");
        properties.setCloudChatModelVersion("deepseek-flash");
        properties.setCloudEnabled(true);
        properties.setLocalChatProvider("ollama");
        properties.setLocalChatModelVersion("qwen3.5:4b");
        properties.setLocalEnabled(true);
        return properties;
    }

    private static ModelProviderPolicy providerPolicy(AiProperties properties) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("cloudModel", mock(OpenAiChatModel.class));
        beans.addBean("localModel", mock(OllamaChatModel.class));

        var fallback = new ChatProviderProperties.Provider();
        fallback.setEnabled(true);
        fallback.setBaseUrl("https://openrouter.ai/api/v1");
        fallback.setModel("xiaomi/mimo-v2.6-flash");
        fallback.setApiKey("test-only-secret");
        fallback.setToolCallingVerified(true);
        fallback.setStructuredOutputVerified(true);
        var chatProviders = new ChatProviderProperties();
        chatProviders.setFallbackOrder(List.of("openrouter"));
        chatProviders.setCatalog(Map.of("openrouter", fallback));

        return new SpringAiProviderConfiguration().modelProviderPolicy(properties, chatProviders,
                beans.getBeanProvider(OpenAiChatModel.class),
                beans.getBeanProvider(OllamaChatModel.class),
                beans.getBeanProvider(TranscriptionModel.class));
    }
}
