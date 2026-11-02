package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.JdbcVehicleCatalogRepository;
import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.api.InventoryQuery;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CatalogJdbcIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    private JdbcClient jdbc;
    private UUID tenantId;
    private UUID variantId;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        tenantId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        var modelId = UUID.randomUUID();
        var syncId = UUID.randomUUID();
        jdbc.sql("insert into sync_run (id, tenant_id, source_system, status, started_at) values (:id, :tenant, 'test', 'COMPLETE', current_timestamp)")
                .param("id", syncId).param("tenant", tenantId).update();
        jdbc.sql("insert into vehicle_model (id, tenant_id, model_code, name, model_year, source_system, source_record_id, observed_at, freshness_status, sync_run_id) values (:id, :tenant, 'RAN', 'Ranger', 2026, 'test', 'model-1', current_timestamp, 'FRESH', :sync)")
                .param("id", modelId).param("tenant", tenantId).param("sync", syncId).update();
        jdbc.sql("insert into vehicle_variant (id, tenant_id, model_id, variant_code, name) values (:id, :tenant, :model, 'XLT', 'XLT')")
                .param("id", variantId).param("tenant", tenantId).param("model", modelId).update();
        jdbc.sql("insert into vehicle_price (id, tenant_id, variant_id, currency, amount, effective_from, source_system, source_record_id, observed_at, freshness_status, sync_run_id) values (:id, :tenant, :variant, 'MXN', 799000.00, current_timestamp - interval '1 day', 'test', 'price-1', current_timestamp, 'FRESH', :sync)")
                .param("id", UUID.randomUUID()).param("tenant", tenantId).param("variant", variantId).param("sync", syncId).update();
        jdbc.sql("insert into vehicle_unit (id, tenant_id, variant_id, external_stock_id, dealership_code, status, observed_at, freshness_status, sync_run_id) values (:id, :tenant, :variant, 'stock-1', 'URUAPAN', 'AVAILABLE', current_timestamp, 'FRESH', :sync)")
                .param("id", UUID.randomUUID()).param("tenant", tenantId).param("variant", variantId).param("sync", syncId).update();
    }

    @Test
    void queriesCatalogAndInventoryWithTenantBoundSql() {
        var repository = new JdbcVehicleCatalogRepository(jdbc);

        assertThat(repository.search(new CatalogQuery(tenantId, "ranger", 2026, null)))
                .singleElement().satisfies(vehicle -> {
                    assertThat(vehicle.modelName()).isEqualTo("Ranger");
                    assertThat(vehicle.currentPrice().amount()).isEqualByComparingTo("799000.00");
                });
        assertThat(repository.inventory(new InventoryQuery(tenantId, "XLT", "URUAPAN"))
                .availableUnits()).isEqualTo(1);
    }

    @Test
    void syntheticCatalogDatabaseIsNeverTheFordServiceDatabase() {
        assertThat(POSTGRES.getDatabaseName()).isNotEqualToIgnoringCase("ford_service");
        assertThat(POSTGRES.getJdbcUrl()).contains("/" + POSTGRES.getDatabaseName());
    }
}
