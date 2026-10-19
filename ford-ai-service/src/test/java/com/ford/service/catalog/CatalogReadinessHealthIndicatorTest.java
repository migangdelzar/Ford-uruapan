package com.ford.service.catalog;

import com.ford.service.catalog.adapter.in.health.CatalogReadinessHealthIndicator;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogReadinessHealthIndicatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogReadinessHealthIndicator.class);

    @Test
    void indicatorIsAbsentWhenCatalogToolsAreDisabled() {
        contextRunner.withPropertyValues("ford.catalog.tools.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CatalogReadinessHealthIndicator.class));
    }

    @Test
    void reportsOnlyAggregateStatusWhenCatalogReadSucceeds() {
        var catalog = mock(CatalogPort.class);
        when(catalog.status()).thenReturn(status());

        contextRunner.withPropertyValues("ford.catalog.tools.enabled=true")
                .withBean(CatalogPort.class, () -> catalog)
                .run(context -> {
                    var health = context.getBean(CatalogReadinessHealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("models", 2L)
                            .containsEntry("documents", 5L)
                            .doesNotContainKeys("content", "sourceUrl", "exception");
                });
    }

    @Test
    void failsClosedWithoutExposingCatalogFailureDetails() {
        var catalog = mock(CatalogPort.class);
        when(catalog.status()).thenThrow(new IllegalStateException("SELECT secret catalog row"));

        contextRunner.withPropertyValues("ford.catalog.tools.enabled=true")
                .withBean(CatalogPort.class, () -> catalog)
                .run(context -> {
                    var health = context.getBean(CatalogReadinessHealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("reason", "catalog_unavailable"));
                    assertThat(health.getDetails().toString()).doesNotContain("secret", "SELECT");
                });
    }

    private static CatalogStatus status() {
        return new CatalogStatus(2, 2, 3, 4, 5, 3, 2, null, "ok", null, null, 4, 2, 3);
    }
}
