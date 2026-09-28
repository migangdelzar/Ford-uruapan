package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class SpringAiProviderConfiguration {

    @Bean
    ModelProviderPolicy modelProviderPolicy(AiProperties properties,
                                            ObjectProvider<OpenAiChatModel> cloudModel,
                                            ObjectProvider<OllamaChatModel> localModel,
                                            ObjectProvider<TranscriptionModel> transcriptionModel) {
        var cloudId = properties.cloudChatProvider();
        var localId = properties.localChatProvider();
        var cloud = cloudModel.getIfAvailable();
        var local = localModel.getIfAvailable();
        var transcriptionAvailable = transcriptionModel.getIfAvailable() != null;
        return new DefaultModelProviderPolicy(List.of(
                new ProviderDescriptor(
                        cloudId,
                        capabilities(properties, cloudId, cloud != null, transcriptionAvailable),
                        properties.cloudEnabled() && cloud != null,
                        true,
                        properties.cloudChatModelVersion()),
                new ProviderDescriptor(
                        localId,
                        capabilities(properties, localId, local != null, false),
                        properties.localEnabled() && local != null,
                        false,
                        properties.localChatModelVersion())));
    }

    private static ProviderCapabilities capabilities(AiProperties properties, String providerId,
                                                    boolean chatAvailable, boolean transcriptionAvailable) {
        return new ProviderCapabilities(true, true, true,
                chatAvailable && properties.allowsMediaProvider(providerId, true, false),
                transcriptionAvailable && providerId.equals(properties.cloudChatProvider())
                        && properties.allowsMediaProvider(providerId, false, true));
    }
}
