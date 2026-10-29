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
    LocalReplayPrecisionEvidence localReplayPrecisionEvidence() {
        return new LocalReplayPrecisionEvidence();
    }

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
            ObjectProvider<ObjectMapper> objectMappers,
            ObjectProvider<LocalReplayPrecisionEvidence> precisionEvidence) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"chatCompletionPort".equals(beanName) || !(bean instanceof ChatCompletionPort delegate)) {
                    return bean;
                }
                var seededDelegate = seededDelegate(delegate);
                return new ReplayPrefetchingChatCompletionPort(seededDelegate, prefetchers.getObject(),
                        vehicleFactPrefetchers.getObject(),
                        promotionPrefetchers.getObject(),
                        objectMappers.getIfAvailable(ObjectMapper::new), precisionEvidence.getIfAvailable(), () -> {
                            var selection = LocalCatalogSnapshotSelection.fromEnvironment(System.getenv());
                            return LocalCatalogSnapshotLoader.manifestSha256(selection.exportDirectory());
                        });
            }
        };
    }

    private static ChatCompletionPort seededDelegate(ChatCompletionPort liveDelegate) {
        var executionMode = LocalQwenReplayExecutionMode.resolve(System.getenv());
        var transcriptPath = System.getenv("FORD_QWEN_REPLAY_SEED_TRANSCRIPT");
        if (executionMode != LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC) return liveDelegate;
        var seedThroughValue = System.getenv("FORD_QWEN_REPLAY_SEED_THROUGH_TURN");
        if (seedThroughValue == null || seedThroughValue.isBlank()) {
            throw new IllegalStateException("FORD_QWEN_REPLAY_SEED_THROUGH_TURN is required with a seed transcript");
        }
        final int seedThroughTurn;
        try {
            seedThroughTurn = Integer.parseInt(seedThroughValue);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("seed-through turn must be an integer", failure);
        }
        try {
            var turns = NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper());
            var seed = LocalQwenTranscriptSeedReader.readTranscript(java.nio.file.Path.of(transcriptPath), turns,
                    seedThroughTurn);
            var selection = LocalCatalogSnapshotSelection.fromEnvironment(System.getenv());
            var activeSnapshotHash = LocalCatalogSnapshotLoader.manifestSha256(selection.exportDirectory());
            validateSeedSnapshotHash(seed.snapshotSha256(), activeSnapshotHash);
            var forcedLiveTurns = java.util.Arrays.stream(System.getenv()
                            .getOrDefault("FORD_QWEN_REPLAY_FORCE_LIVE_TURNS", "").split(","))
                    .map(String::strip).filter(value -> !value.isEmpty()).map(Integer::parseInt)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new LocalQwenSeededChatCompletionPort(liveDelegate, seed.replies(), turns,
                    seedThroughTurn, forcedLiveTurns);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("natural buyer scenario could not be loaded for seed replay", failure);
        }
    }

    static void validateSeedSnapshotHash(String seedHash, String activeHash) {
        if (seedHash == null || activeHash == null || !seedHash.matches("(?i)[a-f0-9]{64}")
                || !activeHash.matches("(?i)[a-f0-9]{64}") || !seedHash.equalsIgnoreCase(activeHash)) {
            throw new IllegalArgumentException("seed transcript snapshot hash mismatch with active catalog");
        }
    }
}
