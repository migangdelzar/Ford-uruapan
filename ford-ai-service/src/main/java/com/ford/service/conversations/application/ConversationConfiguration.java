package com.ford.service.conversations.application;

import com.ford.service.ai.application.AssistantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryAdapter;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryRecallAdapter;
import com.ford.service.conversations.adapter.out.RedisCachedConversationMemoryPort;
import com.ford.service.conversations.adapter.out.RedisConversationMemoryCache;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.port.out.ConversationRepository;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.graph.FordPostgresSaverV2;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.observability.AiObservationService;
import com.ford.service.observability.DefaultAiObservationService;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;

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
    ConversationMemoryRecallPort conversationMemoryRecallPort(
            ObjectProvider<JdbcClient> jdbcClients,
            ObjectProvider<EmbeddingModel> embeddingModels,
            MemoryProperties properties) {
        var jdbc = jdbcClients.getIfAvailable();
        var embeddingModel = embeddingModels.getIfAvailable();
        return !properties.semanticRecallEnabled() || jdbc == null || embeddingModel == null
                ? new NoOpConversationMemoryRecallPort()
                : new PgVectorConversationMemoryRecallAdapter(jdbc, embeddingModel);
    }

    @Bean
    ConversationMemoryService conversationMemoryService(ConversationMemoryPort memory,
                                                        MemoryProperties properties,
                                                        ConversationMemorySemanticPort semanticMemory,
                                                        ApplicationEventPublisher publisher) {
        return new ConversationMemoryService(memory, properties, semanticMemory, publisher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ford.memory", name = "cache-enabled", havingValue = "true")
    ConversationMemoryCache conversationMemoryCache(StringRedisTemplate redis,
                                                     ObjectMapper objectMapper,
                                                     MemoryProperties properties,
                                                     MeterRegistry meterRegistry) {
        return new RedisConversationMemoryCache(redis, objectMapper, properties, meterRegistry);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "ford.memory", name = "cache-enabled", havingValue = "true")
    ConversationMemoryPort cachedConversationMemoryPort(JdbcConversationMemoryRepository durable,
                                                          ConversationMemoryCache cache,
                                                          MemoryProperties properties,
                                                          MeterRegistry meterRegistry) {
        return new RedisCachedConversationMemoryPort(durable, cache, properties, meterRegistry);
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
    @ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "true")
    ConversationGraphRunner conversationGraphRunner(ConversationTurnProcessor turnProcessor,
                                                     ObjectProvider<FordPostgresSaverV2> checkpointSavers) {
        var checkpointSaver = checkpointSavers.getIfAvailable();
        return checkpointSaver == null ? new ConversationGraphRunner(turnProcessor)
                : new ConversationGraphRunner(turnProcessor, checkpointSaver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "true")
    ConversationWorkflow conversationWorkflow(ConversationGraphRunner graphRunner,
                                               ObjectProvider<com.ford.service.conversations.application.port.out.ConversationTurnLeasePort> turnLeases,
                                               ObjectProvider<AiObservationService> observationServices) {
        var observations = observationServices.getIfAvailable(
                () -> new DefaultAiObservationService(ObservationRegistry.NOOP));
        return new ConversationWorkflow(graphRunner, observations, turnLeases.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ford.conversations.workflow", name = "graph-enabled", havingValue = "false", matchIfMissing = true)
    ConversationWorkflow legacyConversationWorkflow(ConversationRepository repository,
                                                     ConversationAssistant assistant,
                                                     ApplicationEventPublisher publisher,
                                                     ObjectProvider<HumanHandoffPolicy> handoffPolicies,
                                                     ConversationMemoryService memoryService,
                                                     ConversationDecisionRepository decisions,
                                                     ObjectProvider<com.ford.service.channels.api.AcceptedInboundMessageRepository> inboundTurns,
                                                     ObjectProvider<com.ford.service.conversations.application.port.out.ConversationTurnLeasePort> turnLeases,
                                                     ObjectProvider<AiObservationService> observationServices) {
        var observations = observationServices.getIfAvailable(
                () -> new DefaultAiObservationService(ObservationRegistry.NOOP));
        var handoffPolicy = handoffPolicies.getIfAvailable(() -> new HumanHandoffPolicy(false));
        return new ConversationWorkflow(repository, assistant, publisher, handoffPolicy, memoryService,
                decisions, observations, inboundTurns.getIfAvailable(), turnLeases.getIfAvailable());
    }
}
