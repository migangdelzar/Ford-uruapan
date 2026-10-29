package com.ford.service.catalog;

import com.ford.service.catalog.adapter.in.health.CatalogReadinessHealthIndicator;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogReadinessHealthIndicatorTest {

    @Test
    void exposesOnlyAggregateReadinessCounts() {
        var catalog = mock(CatalogPort.class);
        when(catalog.status()).thenReturn(new CatalogStatus(
                3, 4, 5, 6, 7, 8, 9, null, "LOCAL_SNAPSHOT", null, null, 0, 0, 0));

        var health = new CatalogReadinessHealthIndicator(catalog).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsOnlyKeys("models", "documents", "embeddedDocuments")
                .containsEntry("models", 3L)
                .containsEntry("documents", 7L)
                .containsEntry("embeddedDocuments", 8L);
    }
}
