package com.ford.service.knowledge.application;

import com.ford.service.knowledge.adapter.out.PgVectorKnowledgeStore;
import com.ford.service.knowledge.application.port.out.KnowledgeRetriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {

    @Bean
    @ConditionalOnBean({VectorStore.class, JdbcClient.class})
    KnowledgeRetriever knowledgeRetriever(VectorStore vectorStore, JdbcClient jdbcClient, ObjectMapper objectMapper) {
        return new PgVectorKnowledgeStore(vectorStore, jdbcClient, objectMapper);
    }

    @Bean
    @ConditionalOnBean(KnowledgeRetriever.class)
    KnowledgeRetrieval knowledgeRetrieval(KnowledgeRetriever retriever) {
        return new KnowledgeRetrievalService(retriever);
    }
}
