package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.adapter.in.tool.CatalogTools;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.ai.application.CatalogNameIndexProperties;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class LocalQwenComparisonPrefetchConfiguration {
    @Bean
    @ConditionalOnProperty(name = "ford.test.local-qwen-comparison-prefetch.enabled", havingValue = "true")
    LocalReplayComparisonPrefetcher localReplayComparisonPrefetcher(
            ObjectProvider<CatalogNameIndexService> nameIndexes, CatalogPort catalog,
            ObjectProvider<CatalogTools> catalogTools,
            ObjectProvider<CatalogRetrievalPort> retrieval) {
        var nameIndex = nameIndexes.getIfAvailable(() -> new CatalogNameIndexService(
                catalog, new CatalogNameIndexProperties(), java.time.Clock.systemUTC()));
        var comparisonTools = catalogTools.getIfAvailable(
                () -> new CatalogTools(catalog, retrieval.getIfAvailable()));
        return new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(), () -> {
            var currentEntries = nameIndex.currentEntries();
            return currentEntries.isEmpty() ? catalog.nameIndexEntries() : currentEntries;
        }, comparisonTools);
    }

    @Bean
    @ConditionalOnProperty(name = "ford.test.local-qwen-comparison-prefetch.enabled", havingValue = "true")
    LocalReplayVehicleFactPrefetcher localReplayVehicleFactPrefetcher(
            ObjectProvider<CatalogNameIndexService> nameIndexes, CatalogPort catalog,
            ObjectProvider<CatalogTools> catalogTools,
            ObjectProvider<CatalogRetrievalPort> retrieval) {
        var nameIndex = nameIndexes.getIfAvailable(() -> new CatalogNameIndexService(
                catalog, new CatalogNameIndexProperties(), java.time.Clock.systemUTC()));
        var tools = catalogTools.getIfAvailable(() -> new CatalogTools(catalog, retrieval.getIfAvailable()));
        return new LocalReplayVehicleFactPrefetcher(new LocalBuyerVehicleResolver(), () -> {
            var currentEntries = nameIndex.currentEntries();
            return currentEntries.isEmpty() ? catalog.nameIndexEntries() : currentEntries;
        }, tools);
    }

    @Bean
    @ConditionalOnProperty(name = "ford.test.local-qwen-comparison-prefetch.enabled", havingValue = "true")
    LocalReplayPromotionPrefetcher localReplayPromotionPrefetcher(
            ObjectProvider<CatalogNameIndexService> nameIndexes, CatalogPort catalog,
            ObjectProvider<CatalogTools> catalogTools, ObjectProvider<CatalogRetrievalPort> retrieval) {
        var nameIndex = nameIndexes.getIfAvailable(() -> new CatalogNameIndexService(
                catalog, new CatalogNameIndexProperties(), java.time.Clock.systemUTC()));
        var tools = catalogTools.getIfAvailable(() -> new CatalogTools(catalog, retrieval.getIfAvailable()));
        return new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(), () -> {
            var currentEntries = nameIndex.currentEntries();
            return currentEntries.isEmpty() ? catalog.nameIndexEntries() : currentEntries;
        }, tools);
    }

    @Bean
    @ConditionalOnProperty(name = "ford.test.local-qwen-comparison-prefetch.enabled", havingValue = "true")
    static BeanPostProcessor localQwenReplayComparisonPrefetchPostProcessor(
            ObjectProvider<LocalReplayComparisonPrefetcher> prefetchers,
            ObjectProvider<LocalReplayVehicleFactPrefetcher> vehicleFactPrefetchers,
            ObjectProvider<LocalReplayPromotionPrefetcher> promotionPrefetchers,
            ObjectProvider<ObjectMapper> objectMappers) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"chatCompletionPort".equals(beanName) || !(bean instanceof ChatCompletionPort delegate)) {
                    return bean;
                }
                return new ReplayPrefetchingChatCompletionPort(delegate, prefetchers.getObject(),
                        vehicleFactPrefetchers.getObject(),
                        promotionPrefetchers.getObject(),
                        objectMappers.getIfAvailable(ObjectMapper::new));
            }
        };
    }
}
