package com.ford.service.catalog.adapter.in.health;

import com.ford.service.catalog.api.CatalogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Content-free readiness check for the dedicated, read-only catalog datasource. */
@Component
@ConditionalOnProperty(prefix = "ford.catalog.tools", name = "enabled", havingValue = "true")
public final class CatalogReadinessHealthIndicator implements HealthIndicator {

    private final CatalogPort catalog;

    public CatalogReadinessHealthIndicator(CatalogPort catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog port is required");
    }

    @Override
    public Health health() {
        try {
            var status = Objects.requireNonNull(catalog.status(), "catalog status is required");
            return Health.up()
                    .withDetail("models", status.modelCount())
                    .withDetail("documents", status.documentCount())
                    .withDetail("embeddedDocuments", status.embeddedDocumentCount())
                    .build();
        } catch (RuntimeException failure) {
            return Health.down().withDetail("reason", "catalog_unavailable").build();
        }
    }
}
