package com.ford.service.conversations.application;

import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConversationConfiguration {

    @Bean
    ConversationAssistant conversationAssistant(ObjectProvider<AssistantService> assistantService) {
        return new ConversationAssistantAdapter(assistantService::getIfAvailable);
    }

    @Bean
    ConversationWorkflow conversationWorkflow(ConversationRepository repository,
                                               ConversationAssistant assistant,
                                               ApplicationEventPublisher eventPublisher) {
        return new ConversationWorkflow(repository, assistant, eventPublisher);
    }
}
