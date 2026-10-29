package com.ford.service.ingestion.adapter.in.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ingestion.adapter.out.ford.AuthorizedFordScraperAdapter;
import com.ford.service.ingestion.adapter.out.ford.FordApiCatalogAdapter;
import com.ford.service.ingestion.adapter.out.ford.FordApiInventoryAdapter;
import com.ford.service.ingestion.adapter.out.imports.CsvImportAdapter;
import com.ford.service.ingestion.application.DefaultSyncCoordinator;
import com.ford.service.ingestion.application.SyncCoordinator;
import com.ford.service.ingestion.application.port.out.AuthorizedScraperSource;
import com.ford.service.ingestion.application.port.out.FordCatalogSource;
import com.ford.service.ingestion.application.port.out.FordInventorySource;
import com.ford.service.ingestion.application.port.out.ManualImportSource;
import com.ford.service.ingestion.application.port.out.CanonicalDataWriter;
import com.ford.service.ingestion.application.port.out.SyncRunRepository;
import com.ford.service.ingestion.domain.SourceNormalization;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.observability.AiObservationService;
import com.ford.service.shared.DefaultSourcePrecedencePolicy;
import com.ford.service.shared.SourcePrecedencePolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Configuration
@ConditionalOnProperty(name = "ford.ingestion.enabled", havingValue = "true")
public class FordIngestionConfiguration {

    @Bean
    SourcePrecedencePolicy sourcePrecedencePolicy() {
        return new DefaultSourcePrecedencePolicy();
    }

    @Bean
    RestClient fordIngestionRestClient() { return RestClient.builder().build(); }

    @Bean
    FordCatalogSource fordCatalogSource(RestClient client, ObjectMapper mapper, FordSyncProperties properties) {
        return properties.catalogEndpoint().isBlank() ? request -> emptySnapshot()
                : new FordApiCatalogAdapter(client, properties.catalogEndpoint(), mapper);
    }

    @Bean
    FordInventorySource fordInventorySource(RestClient client, ObjectMapper mapper, FordSyncProperties properties) {
        return properties.inventoryEndpoint().isBlank() ? request -> emptySnapshot()
                : new FordApiInventoryAdapter(client, properties.inventoryEndpoint(), mapper);
    }

    @Bean
    AuthorizedScraperSource authorizedScraperSource(RestClient client, ObjectMapper mapper, FordSyncProperties properties) {
        return properties.scraperEndpoint().isBlank() ? request -> emptySnapshot()
                : new AuthorizedFordScraperAdapter(client, properties.scraperEndpoint(), mapper);
    }

    @Bean
    ManualImportSource manualImportSource(FordSyncProperties properties) {
        return properties.manualCsvPath().isBlank() ? request -> emptySnapshot()
                : new CsvImportAdapter(Path.of(properties.manualCsvPath()));
    }

    @Bean
    SourceNormalization sourceNormalization(ObjectMapper mapper) { return new SourceNormalization(mapper); }

    @Bean
    SyncCoordinator syncCoordinator(FordCatalogSource catalog, FordInventorySource inventory,
                                    AuthorizedScraperSource scraper, ManualImportSource manual,
                                    SyncRunRepository repository, SourceNormalization normalization,
                                    FordSyncProperties properties, AiObservationService observations,
                                    CanonicalDataWriter canonicalDataWriter,
                                    SourcePrecedencePolicy sourcePrecedencePolicy) {
        return new DefaultSyncCoordinator(catalog, inventory, scraper, manual, repository,
                normalization, properties.maxAttempts(), observations, canonicalDataWriter, sourcePrecedencePolicy);
    }

    @Bean
    FordSyncJob fordSyncJob(SyncCoordinator coordinator, FordSyncProperties properties,
                            com.ford.service.shared.PostgresAdvisoryExecutionLock executionLock) {
        return new FordSyncJob(coordinator, properties, executionLock);
    }

    private static SourceSnapshot emptySnapshot() {
        return new SourceSnapshot(UUID.randomUUID(), List.of());
    }
}
