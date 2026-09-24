package com.ford.service.ai.configuration;

import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.adapter.out.springai.SpringAiProviderConfiguration;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root marker for Spring AI adapters and read-only tool wiring. */
@Configuration(proxyBeanMethods = false)
public class SpringAiConfiguration {

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    ChatCompletionPort chatCompletionPort(ChatClient.Builder chatClientBuilder,
                                          SystemPromptService systemPrompt,
                                          SpringAiProviderConfiguration.Properties properties,
                                          @Value("${spring.ai.model.chat:unknown}") String provider) {
        var providerId = "unknown".equals(provider) ? properties.localChatProvider() : provider;
        return new SpringAiChatCompletionAdapter(chatClientBuilder.build(), providerId, systemPrompt);
    }
}
