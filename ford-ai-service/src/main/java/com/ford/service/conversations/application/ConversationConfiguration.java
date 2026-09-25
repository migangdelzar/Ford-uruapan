package com.ford.service.conversations.application;

import com.ford.service.ai.application.AssistantService;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryAdapter;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.shared.HumanHandoffPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ConversationProperties.class, MemoryProperties.class})
public class ConversationConfiguration {

    @Bean
    ConversationMemorySemanticPort conversationMemorySemanticPort(
            ObjectProvider<JdbcClient> jdbcClients, ObjectProvider<EmbeddingModel> embeddingModels) {
        var jdbc = jdbcClients.getIfAvailable();
        var embeddingModel = embeddingModels.getIfAvailable();
        return jdbc == null || embeddingModel == null
                ? new NoOpConversationMemorySemanticPort()
                : new PgVectorConversationMemoryAdapter(jdbc, embeddingModel);
    }

    @Bean
    ConversationMemoryService conversationMemoryService(ConversationMemoryPort memory,
                                                        MemoryProperties properties,
                                                        ConversationMemorySemanticPort semanticMemory) {
        return new ConversationMemoryService(memory, properties, semanticMemory);
    }

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
                                               HumanHandoffPolicy handoffPolicy,
                                               ConversationMemoryService memoryService,
                                               ConversationDecisionRepository decisionRepository) {
        return new ConversationWorkflow(repository, assistant, eventPublisher, handoffPolicy, memoryService,
                decisionRepository);
    }
}
