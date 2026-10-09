package com.ford.service.ai.configuration;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleChatModelFactoryTest {

    @Test
    void buildsAnOpenAiCompatibleClientWithProviderEndpointAndNoInternalRetries() {
        var provider = new ChatProviderProperties.Provider();
        provider.setBaseUrl("https://api.groq.com/openai/v1");
        provider.setModel("openai/gpt-oss-20b");
        provider.setApiKey("test-only-secret");

        var model = OpenAiCompatibleChatModelFactory.create("groq", provider, ObservationRegistry.NOOP);

        assertThat(model.getOptions().getBaseUrl()).isEqualTo("https://api.groq.com/openai/v1");
        assertThat(model.getOptions().getModel()).isEqualTo("openai/gpt-oss-20b");
        assertThat(model.getOptions().getMaxRetries()).isZero();
        assertThat(model.getOptions().getMaxTokens()).isEqualTo(1024);
        assertThat(model.getOptions().getExtraBody()).containsEntry("include_reasoning", false);
    }

    @Test
    void doesNotSendGroqSpecificReasoningOptionsToOtherProviders() {
        var provider = new ChatProviderProperties.Provider();
        provider.setBaseUrl("https://openrouter.ai/api/v1");
        provider.setModel("openai/gpt-oss-120b");
        provider.setApiKey("test-only-secret");

        var model = OpenAiCompatibleChatModelFactory.create("openrouter", provider, ObservationRegistry.NOOP);

        assertThat(model.getOptions().getExtraBody()).isNull();
    }
}
