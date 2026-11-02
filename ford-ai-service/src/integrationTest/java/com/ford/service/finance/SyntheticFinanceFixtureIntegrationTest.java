package com.ford.service.finance;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.finance.adapter.out.JdbcFinanceTermsRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SyntheticFinanceFixtureIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    private static JdbcClient jdbc;
    private static JdbcFinanceTermsRepository terms;
    private UUID tenantId;
    private UUID otherTenantId;

    @BeforeAll
    static void initializeIsolatedFixtureDatabase() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(source);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(source);
        terms = new JdbcFinanceTermsRepository(jdbc);
    }

    @BeforeEach
    void seedSyntheticTenantTerms() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        var product = UUID.randomUUID();
        jdbc.sql("""
                insert into finance_product
                  (id, tenant_id, lender_code, product_code, name, annual_rate, opening_fee, insurance_fee,
                   effective_from, effective_to, requirements, source_system)
                values (:id, :tenant, 'SYNTHETIC_LENDER', 'SYNTHETIC_FIXED_60', 'Synthetic fixed fixture',
                        0.12500, 1500.00, 750.00, current_timestamp - interval '1 day',
                        current_timestamp + interval '30 days', '[]'::jsonb, 'synthetic-fixture')
                """)
                .param("id", product).param("tenant", tenantId).update();
        jdbc.sql("insert into finance_term (id, finance_product_id, months, annual_rate) "
                        + "values (:id, :product, 60, 0.11250)")
                .param("id", UUID.randomUUID()).param("product", product).update();
        var otherProduct = UUID.randomUUID();
        jdbc.sql("""
                insert into finance_product
                  (id, tenant_id, lender_code, product_code, name, annual_rate, opening_fee, insurance_fee,
                   effective_from, effective_to, requirements, source_system)
                values (:id, :tenant, 'SYNTHETIC_LENDER', 'SYNTHETIC_FIXED_60', 'Other tenant decoy',
                        0.01000, 0, 0, current_timestamp - interval '1 day',
                        current_timestamp + interval '30 days', '[]'::jsonb, 'synthetic-fixture')
                """)
                .param("id", otherProduct).param("tenant", otherTenantId).update();
    }

    @Test
    void returnsOnlyActiveSyntheticTermsForTheRequestedTenant() {
        var found = terms.findActive(tenantId, "SYNTHETIC_FIXED_60", 60);

        assertThat(found).isPresent().get().satisfies(value -> {
            assertThat(value.annualRate()).isEqualByComparingTo("0.11250");
            assertThat(value.openingFee().amount()).isEqualByComparingTo("1500.00");
            assertThat(value.insuranceFee().amount()).isEqualByComparingTo("750.00");
        });
        assertThat(terms.findActive(otherTenantId, "SYNTHETIC_FIXED_60", 60))
                .isPresent().get().satisfies(value ->
                        assertThat(value.annualRate()).isEqualByComparingTo("0.01000"));
        assertThat(terms.findActive(UUID.randomUUID(), "SYNTHETIC_FIXED_60", 60)).isEmpty();
    }

    @Test
    void listsOnlyTheTenantActiveFinancePlansAndPublishedTerms() {
        var available = terms.findAvailable(tenantId);

        assertThat(available).singleElement().satisfies(plan -> {
            assertThat(plan.productCode()).isEqualTo("SYNTHETIC_FIXED_60");
            assertThat(plan.name()).isEqualTo("Synthetic fixed fixture");
            assertThat(plan.lenderCode()).isEqualTo("SYNTHETIC_LENDER");
            assertThat(plan.termMonths()).isEqualTo(60);
            assertThat(plan.annualRate()).isEqualByComparingTo("0.11250");
        });
        assertThat(terms.findAvailable(UUID.randomUUID())).isEmpty();
    }
}
