package com.ford.service.conversations.application;

import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.shared.HumanHandoffPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConversationProperties.class)
public class ConversationConfiguration {

    @Bean
    HumanHandoffPolicy humanHandoffPolicy(ConversationProperties properties) {
        return new HumanHandoffPolicy(properties.humanHandoffEnabled());
    }

    @Bean
    ConversationAssistant conversationAssistant(AssistantService assistantService) {
        return new ConversationAssistantAdapter(assistantService);
    }

    @Bean
    ConversationWorkflow conversationWorkflow(ConversationRepository repository,
                                               ConversationAssistant assistant,
                                               ApplicationEventPublisher eventPublisher,
                                               HumanHandoffPolicy handoffPolicy) {
        return new ConversationWorkflow(repository, assistant, eventPublisher, handoffPolicy);
    }
}
