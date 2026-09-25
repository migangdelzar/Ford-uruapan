package com.ford.service.ingestion;

import com.ford.service.ingestion.application.port.out.CanonicalDataWriter;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.SourceCandidate;
import com.ford.service.shared.SourceKind;
import com.ford.service.shared.SourceMetadata;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "spring.modulith.runtime.verification-enabled=false")
@ActiveProfiles("test")
class CanonicalIngestionIntegrationTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-19T15:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();

    @BeforeAll
    static void prepareSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    @Autowired
    CanonicalDataWriter writer;

    @Autowired
    JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from vehicle_unit").update();
        jdbc.sql("delete from vehicle_price").update();
        jdbc.sql("delete from vehicle_variant").update();
        jdbc.sql("delete from vehicle_model").update();
        jdbc.sql("delete from sync_run").update();
    }

    @Test
    void upsertsCatalogPriceAndInventoryWithoutDuplicatingSourceRows() {
        var tenantId = UUID.randomUUID();
        var firstRun = startRun(tenantId);
        var catalog = candidate("ford-api-catalog", "ranger-2026", """
                {"modelCode":"RANGER","modelName":"Ranger","modelYear":2026,
                 "variantCode":"RANGER-XLT","variantName":"XLT","price":589000,
                 "specifications":{"engine":"2.3L"}}
                """);

        assertThat(writer.write(tenantId, firstRun, List.of(catalog))).isEqualTo(1);
        var secondRun = startRun(tenantId);
        assertThat(writer.write(tenantId, secondRun, List.of(catalog))).isEqualTo(1);

        var inventory = candidate("ford-api-inventory", "stock-1", """
                {"variantCode":"RANGER-XLT","stockId":"stock-1",
                 "dealershipCode":"URUAPAN","status":"AVAILABLE"}
                """);
        assertThat(writer.write(tenantId, secondRun, List.of(inventory))).isEqualTo(1);

        assertThat(count("vehicle_model", tenantId)).isEqualTo(1);
        assertThat(count("vehicle_variant", tenantId)).isEqualTo(1);
        assertThat(count("vehicle_price", tenantId)).isEqualTo(1);
        assertThat(count("vehicle_unit", tenantId)).isEqualTo(1);
        assertThat(jdbc.sql("select amount from vehicle_price where tenant_id = :tenantId")
                .param("tenantId", tenantId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("589000");
    }

    private UUID startRun(UUID tenantId) {
        var runId = UUID.randomUUID();
        jdbc.sql("""
                insert into sync_run (id, tenant_id, source_system, status, started_at)
                values (:id, :tenantId, 'ford-ingestion', 'RUNNING', :startedAt)
                """)
                .param("id", runId).param("tenantId", tenantId)
                .param("startedAt", Timestamp.from(OBSERVED_AT)).update();
        return runId;
    }

    private SourceCandidate candidate(String source, String recordId, String payload) {
        return new SourceCandidate(SourceKind.OFFICIAL_FEED, payload,
                new SourceMetadata(source, recordId, OBSERVED_AT, null, null,
                        FreshnessStatus.FRESH, payload, UUID.randomUUID()));
    }

    private int count(String table, UUID tenantId) {
        return jdbc.sql("select count(*) from " + table + " where tenant_id = :tenantId")
                .param("tenantId", tenantId).query(Integer.class).single();
    }
}
