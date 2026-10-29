package com.ford.service.conversations.application;

import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.InputSecurityPolicy;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.conversations.adapter.out.ai.ProviderRoutedConversationMemoryCompactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryAdapter;
import com.ford.service.conversations.adapter.out.PgVectorConversationMemoryRecallAdapter;
import com.ford.service.conversations.adapter.out.RedisCachedConversationMemoryPort;
import com.ford.service.conversations.adapter.out.RedisConversationMemoryCache;
import com.ford.service.conversations.application.port.out.ConversationAssistant;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnProcessor;
import com.ford.service.conversations.application.graph.FordPostgresSaverV2;
import com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.ford.service.conversations.api.CustomerIdentityFingerprint;
import com.ford.service.conversations.application.CustomerMemoryFactExtractor;
import com.ford.service.conversations.application.CustomerMemoryUpdatePolicy;
import com.ford.service.conversations.application.CustomerMemoryService;
import com.ford.service.conversations.application.CustomerMemoryProperties;
import com.ford.service.conversations.application.CustomerMemoryFactExtractor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ConversationProperties.class, MemoryProperties.class, CustomerMemoryProperties.class})
public class ConversationConfiguration {

    @Bean
    CustomerIdentityFingerprint customerIdentityFingerprint() {
        return new CustomerIdentityFingerprint();
    }

    @Bean
    CustomerMemoryFactExtractor customerMemoryFactExtractor() {
        return new CustomerMemoryFactExtractor();
    }

    @Bean
    CustomerMemoryUpdatePolicy customerMemoryUpdatePolicy() {
        return new CustomerMemoryUpdatePolicy();
    }

    @Bean
    @ConditionalOnBean({
            com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository.class,
            com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository.class
    })
    CustomerMemoryService customerMemoryService(CustomerMemoryProperties properties,
            CustomerIdentityFingerprint fingerprint, CustomerMemoryFactExtractor extractor,
            CustomerMemoryUpdatePolicy policy,
            com.ford.service.conversations.application.port.out.CustomerMemoryIdentityRepository identities,
            com.ford.service.conversations.application.port.out.CustomerMemoryFactRepository facts) {
        return new CustomerMemoryService(properties, fingerprint, extractor, policy, identities, facts);
    }

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
    ConversationMemoryCompactionPort conversationMemoryCompactionPort(
            ObjectProvider<ChatCompletionPort> completions, ObjectProvider<InputSecurityPolicy> inputSecurity) {
        var completion = completions.getIfAvailable();
        var security = inputSecurity.getIfAvailable();
        return completion == null || security == null ? ConversationMemoryCompactionPort.unavailable()
                : new ProviderRoutedConversationMemoryCompactor(completion, security);
    }

    @Bean
    ConversationMemoryService conversationMemoryService(ConversationMemoryPort memory,
                                                        MemoryProperties properties,
                                                        ConversationMemorySemanticPort semanticMemory,
                                                        ConversationMemoryRecallPort recall,
                                                         ApplicationEventPublisher publisher,
                                                         ConversationMemoryCompactionPort compactor,
                                                         ObjectProvider<ConversationRuntimeSettingsService> runtimeSettings,
                                                         ObjectProvider<CustomerMemoryService> customerMemory) {
        return new ConversationMemoryService(memory, properties, semanticMemory, recall, publisher, compactor,
                runtimeSettings.getIfAvailable(), customerMemory.getIfAvailable());
    }

    @Bean
    ConversationRuntimeSettings runtimeSettingsDefaults(ConversationProperties conversations, MemoryProperties memory) {
        return new ConversationRuntimeSettings(conversations.humanHandoffEnabled(), memory.enabled(),
                memory.promptRecentMessageLimit());
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
    HumanHandoffPolicy humanHandoffPolicy(ConversationProperties properties,
                                          ObjectProvider<ConversationRuntimeSettingsService> runtimeSettings) {
        var runtime = runtimeSettings.getIfAvailable();
        return runtime == null ? new HumanHandoffPolicy(properties.humanHandoffEnabled())
                : new HumanHandoffPolicy(properties.humanHandoffEnabled(), tenantId ->
                        runtime.current(tenantId).settings().humanHandoffEnabled());
    }

    @Bean
    DealershipHoursPolicy dealershipHoursPolicy(ConversationProperties properties,
                                                ObjectProvider<java.time.Clock> clocks) {
        var clock = clocks.getIfAvailable(() -> java.time.Clock.systemUTC());
        return DealershipHoursFactory.create(clock, properties.hours());
    }

    @Bean
    DealershipResponsePlanner dealershipResponsePlanner(DealershipHoursPolicy dealershipHoursPolicy,
                                                        ObjectProvider<IntentRouter> intentRouters) {
        return new DealershipResponsePlanner(dealershipHoursPolicy,
                intentRouters.getIfAvailable(IntentRouter::new));
    }

    @Bean
    PendingTestDriveReplyPolicy pendingTestDriveReplyPolicy() {
        return new PendingTestDriveReplyPolicy();
    }

    @Bean
    ConversationAssistant conversationAssistant(AssistantService assistantService,
                                                PendingTestDriveReplyPolicy pendingTestDriveReplyPolicy,
                                                ObjectProvider<SharedCustomerFactProvider> sharedFacts,
                                                MemoryProperties memoryProperties) {
        return new ConversationAssistantAdapter(assistantService, pendingTestDriveReplyPolicy,
                sharedFacts.getIfAvailable(), memoryProperties);
    }

    @Bean
    ConversationGraphRunner conversationGraphRunner(ConversationTurnProcessor turnProcessor,
                                                     ObjectProvider<FordPostgresSaverV2> checkpointSavers) {
        var checkpointSaver = checkpointSavers.getIfAvailable();
        return new ConversationGraphRunner(turnProcessor, checkpointSaver);
    }

    @Bean
    ConversationWorkflow conversationWorkflow(ConversationGraphRunner graphRunner,
                                               ConversationTurnLedgerPort turnLedger,
                                                ObjectProvider<com.ford.service.conversations.application.port.out.ConversationTurnLeasePort> turnLeases,
                                                 ObjectProvider<com.ford.service.channels.api.InboundDebounceGate> debounceGates,
                                                 ObjectProvider<AiObservationService> observationServices) {
        var observations = observationServices.getIfAvailable(
                () -> new DefaultAiObservationService(ObservationRegistry.NOOP));
        return new ConversationWorkflow(graphRunner, observations, turnLeases.getIfAvailable(),
                debounceGates.getIfAvailable(), turnLedger);
    }
}
