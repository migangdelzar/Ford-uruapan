package com.ford.service.ai.configuration;

import com.ford.service.ai.adapter.out.springai.KnowledgeRagAdvisorFactory;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeRagProperties.class)
public class KnowledgeRagAdvisorConfiguration {

    @Bean
    @ConditionalOnBean(KnowledgeRetrieval.class)
    KnowledgeRagAdvisorFactory knowledgeRagAdvisorFactory(
            KnowledgeRetrieval retrieval,
            ObjectProvider<TypeSafeClient> typeSafeClients,
            KnowledgeRagProperties properties) {
        List<DocumentPostProcessor> postProcessors = List.of();
        if (properties.rerankerEnabled()) {
            var typeSafeClient = typeSafeClients.getIfAvailable();
            if (typeSafeClient == null) {
                throw new IllegalStateException("Knowledge reranking is enabled but TypeSafe is unavailable");
            }
            postProcessors = List.of(JevDocumentReranker.builder(typeSafeClient)
                    .topK(properties.rerankerTopK())
                    .build());
        }
        return new KnowledgeRagAdvisorFactory(retrieval, postProcessors);
    }
}
