package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
                                            ObjectProvider<OllamaChatModel> localModel) {
        return new DefaultModelProviderPolicy(List.of(
                new ProviderDescriptor(
                        properties.cloudChatProvider(),
                        new ProviderCapabilities(true, true, true),
                        properties.cloudEnabled() && cloudModel.getIfAvailable() != null,
                        true,
                        properties.cloudChatModelVersion()),
                new ProviderDescriptor(
                        properties.localChatProvider(),
                        new ProviderCapabilities(true, true, true),
                        properties.localEnabled() && localModel.getIfAvailable() != null,
                        false,
                        properties.localChatModelVersion())));
    }
}
