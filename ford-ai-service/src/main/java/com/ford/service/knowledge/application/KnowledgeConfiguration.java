package com.ford.service.knowledge.application;

import com.ford.service.knowledge.adapter.out.PgVectorKnowledgeStore;
import com.ford.service.knowledge.application.port.out.KnowledgeRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {

    @Bean
    @ConditionalOnBean(VectorStore.class)
    KnowledgeRetriever knowledgeRetriever(VectorStore vectorStore) {
        return new PgVectorKnowledgeStore(vectorStore);
    }

    @Bean
    @ConditionalOnBean(KnowledgeRetriever.class)
    KnowledgeRetrieval knowledgeRetrieval(KnowledgeRetriever retriever) {
        return new KnowledgeRetrievalService(retriever);
    }
}
