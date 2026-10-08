package com.ford.service.ai.configuration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

/** Builds isolated Spring AI OpenAI-compatible clients for approved fallback providers. */
final class OpenAiCompatibleChatModelFactory {

    private OpenAiCompatibleChatModelFactory() {
    }

    static OpenAiChatModel create(String providerId, ChatProviderProperties.Provider provider,
                                  ObservationRegistry registry) {
        return create(providerId, provider, registry, 0.1);
    }

    static OpenAiChatModel create(String providerId, ChatProviderProperties.Provider provider,
                                  ObservationRegistry registry, double temperature) {
        var builder = OpenAiChatOptions.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey())
                .model(provider.model())
                .temperature(temperature)
                .maxRetries(0);
        if ("groq".equals(providerId)
                && (provider.model().startsWith("openai/gpt-oss-")
                || provider.model().startsWith("qwen/qwen3.8-"))) {
            builder.extraBody(Map.of("include_reasoning", false));
        }
        var options = builder.build();
        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(registry)
                .build();
    }
}
