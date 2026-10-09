package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.configuration.ChatProviderProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.ArrayList;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProperties.class, ChatProviderProperties.class})
public class SpringAiProviderConfiguration {

    @Bean
    ModelProviderPolicy modelProviderPolicy(AiProperties properties,
                                            ChatProviderProperties chatProviders,
                                            ObjectProvider<OpenAiChatModel> cloudModel,
                                            ObjectProvider<OllamaChatModel> localModel,
                                            ObjectProvider<TranscriptionModel> transcriptionModel) {
        var cloudId = properties.cloudChatProvider();
        var localId = properties.localChatProvider();
        var cloud = cloudModel.getIfAvailable();
        var local = localModel.getIfAvailable();
        var transcriptionAvailable = transcriptionModel.getIfAvailable() != null;
        var providers = new ArrayList<ProviderDescriptor>();
        if ("local".equals(properties.modelProfile())) {
            providers.add(new ProviderDescriptor(localId,
                    capabilities(properties, localId, local != null, false),
                    properties.localEnabled() && local != null, false, properties.localChatModelVersion()));
        } else {
            providers.add(new ProviderDescriptor(cloudId,
                    capabilities(properties, cloudId, cloud != null, transcriptionAvailable),
                    properties.cloudEnabled() && cloud != null, true, properties.cloudChatModelVersion()));
        }
        for (var providerId : chatProviders.eligibleFallbackIds()) {
            var provider = chatProviders.catalog().get(providerId);
            providers.add(new ProviderDescriptor(providerId,
                    new ProviderCapabilities(provider.toolCallingVerified(), provider.structuredOutputVerified(), true),
                    true, true, provider.model()));
        }
        return new DefaultModelProviderPolicy(providers);
    }

    private static ProviderCapabilities capabilities(AiProperties properties, String providerId,
                                                    boolean chatAvailable, boolean transcriptionAvailable) {
        return new ProviderCapabilities(true, true, true,
                chatAvailable && properties.allowsMediaProvider(providerId, true, false),
                transcriptionAvailable && providerId.equals(properties.cloudChatProvider())
                        && properties.allowsMediaProvider(providerId, false, true));
    }
}
