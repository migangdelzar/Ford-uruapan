package com.ford.service.catalog;

import com.ford.service.catalog.adapter.out.JdbcFordCatalogReader;
import com.ford.service.catalog.adapter.out.JdbcFordCatalogKnowledgeReader;
import com.ford.service.catalog.domain.CatalogFilters;
import com.ford.service.catalog.domain.CatalogDocumentHit;
import com.ford.service.catalog.domain.CatalogModelSearchResult;
import com.ford.service.database.FordPostgresContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcFordCatalogReaderIntegrationTest {

    @Container
    static final FordPostgresContainer POSTGRES = new FordPostgresContainer();

    private static JdbcClient jdbc;
    private static JdbcFordCatalogReader reader;
    private static JdbcFordCatalogKnowledgeReader knowledgeReader;
    private static final UUID MODEL_YEAR_ID = UUID.fromString("15000000-0000-0000-0000-000000000001");
    private static final UUID MUSTANG_MODEL_YEAR_ID = UUID.fromString("15000000-0000-0000-0000-000000000002");
    private static final UUID BRONCO_MODEL_ID = UUID.fromString("05000000-0000-0000-0000-000000000001");
    private static final UUID MUSTANG_MODEL_ID = UUID.fromString("05000000-0000-0000-0000-000000000002");
    private static final UUID BRONCO_VARIANT_ID = UUID.fromString("25000000-0000-0000-0000-000000000001");
    private static final UUID MUSTANG_VARIANT_ID = UUID.fromString("25000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void createCatalogContract() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("create schema if not exists ford_catalog").update();
        jdbc.sql("""
                create table ford_catalog.vehicle_search_v1 (
                    model_year_id uuid primary key,
                    model text not null,
                    normalized_name text not null,
                    year integer not null,
                    price numeric,
                    currency text,
                    category text,
                    body_type text,
                    fuel_type text,
                    model_page_url text,
                    price_source_url text,
                    observed_at timestamptz
                )
                """).update();
        jdbc.sql("""
                create table ford_catalog.vehicle_variants_v2 (
                    variant_id uuid primary key,
                    model_year_id uuid not null,
                    model text not null,
                    normalized_model text not null,
                    year integer not null,
                    trim text not null,
                    normalized_trim text not null,
                    price numeric,
                    currency text,
                    model_page_url text,
                    price_source_url text,
                    observed_at timestamptz,
                    comparison_url text
                )
                """).update();
        jdbc.sql("""
                create table ford_catalog.vehicle_facts_v2 (
                    variant_id uuid not null,
                    model_year_id uuid not null,
                    model text not null,
                    year integer not null,
                    trim text not null,
                    category text,
                    fact_type text not null,
                    fact_name text not null,
                    fact_name_normalized text not null,
                    fact_value text not null,
                    value_numeric numeric,
                    fact_unit text,
                    source_url text,
                    observed_at timestamptz,
                    model_page_url text
                )
                """).update();
        jdbc.sql("""
                create table ford_catalog.offers_v2 (
                    promotion_id uuid primary key,
                    model text not null,
                    year integer not null,
                    title text not null,
                    description text,
                    discount_amount numeric,
                    discount_percent numeric,
                    cash_price numeric,
                    currency text,
                    valid_from date,
                    valid_until date,
                    conditions text,
                    offer_type text,
                    provider text,
                    down_payment_amount numeric,
                    down_payment_percent numeric,
                    monthly_payment numeric,
                    term_months integer,
                    months_without_interest integer,
                    annual_interest_rate numeric,
                    cat_percent numeric,
                    requires_credit_approval boolean,
                    source_url text,
                    observed_at timestamptz,
                    model_page_url text,
                    benefits jsonb,
                    term_conflict jsonb,
                    tax_rate_percent numeric,
                    opening_commission_rate_percent numeric,
                    maximum_down_payment_percent numeric,
                    life_insurance_price numeric
                )
                """).update();
        jdbc.sql("""
                create table ford_catalog.catalog_documents_v1 (
                    document_id uuid primary key,
                    document_type text not null,
                    document_key text not null,
                    title text not null,
                    semantic_content text not null,
                    model_id uuid,
                    model_year_id uuid,
                    variant_id uuid,
                    offer_id uuid,
                    source_url text,
                    observed_at timestamptz,
                    embedding_model text not null,
                    embedding_status text not null,
                    embedding vector(1024),
                    search_vector tsvector not null
                )
                """).update();
        jdbc.sql("create schema if not exists ford_metadata").update();
        jdbc.sql("create table ford_metadata.private_notes (note text)").update();
        jdbc.sql("create table ford_catalog.retrieval_document_manifest (document_id uuid, source_url text)").update();
        jdbc.sql("grant usage on schema ford_catalog to ford_catalog_reader").update();
        jdbc.sql("grant select on ford_catalog.vehicle_search_v1, ford_catalog.vehicle_variants_v2, ford_catalog.vehicle_facts_v2, ford_catalog.offers_v2, ford_catalog.catalog_documents_v1 to ford_catalog_reader").update();
    }

    @BeforeEach
    void seedCatalogRows() {
        jdbc.sql("truncate ford_catalog.vehicle_facts_v2, ford_catalog.vehicle_variants_v2, ford_catalog.vehicle_search_v1, ford_catalog.offers_v2, ford_catalog.catalog_documents_v1")
                .update();
        jdbc.sql("insert into ford_catalog.vehicle_search_v1 (model_year_id, model, normalized_name, year, price, currency, category, body_type, fuel_type, model_page_url, price_source_url, observed_at) values (:id, :model, lower(:model), 2026, 1000000, 'MXN', 'suv', 'SUV', 'gasoline', 'https://ford.example/model', 'https://ford.example/price', current_timestamp)")
                .param("id", MODEL_YEAR_ID).param("model", "BRONCO").update();
        jdbc.sql("insert into ford_catalog.vehicle_search_v1 (model_year_id, model, normalized_name, year, price, currency, category, body_type, fuel_type, model_page_url, price_source_url, observed_at) values (:id, :model, lower(:model), 2026, 1000000, 'MXN', 'sports car', 'coupe', 'gasoline', 'https://ford.example/model', 'https://ford.example/price', current_timestamp)")
                .param("id", MUSTANG_MODEL_YEAR_ID).param("model", "MUSTANG").update();
        insertVariant(BRONCO_VARIANT_ID, "BRONCO", "Big Bend", "https://ford.example/bronco/big-bend");
        insertVariant(MUSTANG_VARIANT_ID, "MUSTANG", "GT", "https://ford.example/mustang/gt");
        jdbc.sql("insert into ford_catalog.vehicle_facts_v2 (variant_id, model_year_id, model, year, trim, fact_type, fact_name, fact_name_normalized, fact_value, source_url) values (:variant, :modelYear, 'BRONCO', 2026, 'Big Bend', 'specification', 'Color exterior', 'color_exterior', 'Azul relámpago', 'https://ford.example/bronco/colors')")
                .param("variant", BRONCO_VARIANT_ID).param("modelYear", MODEL_YEAR_ID).update();
        jdbc.sql("insert into ford_catalog.offers_v2 (promotion_id, model, year, title, valid_from, valid_until, source_url) values (:id, 'BRONCO', 2026, 'Bono de lanzamiento', current_date - 1, current_date + 1, 'https://ford.example/bronco/offer')")
                .param("id", UUID.fromString("35000000-0000-0000-0000-000000000001")).update();
        var catalogDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=ford_catalog",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var catalogJdbc = JdbcClient.create(catalogDataSource);
        reader = new JdbcFordCatalogReader(catalogJdbc);
        knowledgeReader = new JdbcFordCatalogKnowledgeReader(catalogJdbc,
                text -> vectorValues(1));
    }

    @Test
    void findsExactModelYearPriceAndColorWithSourceUrl() {
        var results = reader.searchStructuredCatalog(new CatalogFilters(
                "bronco", 2026, new BigDecimal("900000"), new BigDecimal("1200000"),
                "azul relámpago", "suv", "SUV", "gasoline"), 10);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.variantId()).isEqualTo(BRONCO_VARIANT_ID);
            assertThat(result.model()).isEqualTo("BRONCO");
            assertThat(result.variant()).isEqualTo("Big Bend");
            assertThat(result.price()).isEqualByComparingTo("1000000.00");
            assertThat(result.color()).isEqualTo("Azul relámpago");
            assertThat(result.sourceUrl()).isEqualTo("https://ford.example/bronco/big-bend");
        });
    }

    @Test
    void doesNotTreatColorAsSemanticApproximation() {
        var results = reader.searchStructuredCatalog(new CatalogFilters(
                "bronco", 2026, null, null, "rojo", null, null, null), 10);

        assertThat(results).isEmpty();
    }

    @Test
    void returnsOnlyCurrentModelYearOffersWithCitations() {
        var offers = reader.listCurrentOffers(MODEL_YEAR_ID);

        assertThat(offers).singleElement().satisfies(offer -> {
            assertThat(offer.title()).isEqualTo("Bono de lanzamiento");
            assertThat(offer.sourceUrl()).isEqualTo("https://ford.example/bronco/offer");
        });
    }

    @Test
    void readsRequestedVariantWithoutLoadingAnUnrelatedFirstPage() {
        for (int index = 0; index < 101; index++) {
            insertVariant(UUID.nameUUIDFromBytes(("bronco-" + index).getBytes()),
                    "BRONCO", "Trim " + index, "https://ford.example/bronco/" + index);
        }

        assertThat(reader.getCatalogVariant(MUSTANG_VARIANT_ID))
                .isPresent()
                .get()
                .extracting(result -> result.variantId())
                .isEqualTo(MUSTANG_VARIANT_ID);
    }

    @Test
    void boundsStructuredResultLimit() {
        assertThat(reader.searchStructuredCatalog(CatalogFilters.empty(), 0)).isEmpty();
        for (int index = 0; index < 105; index++) {
            insertVariant(UUID.nameUUIDFromBytes(("bronco-limit-" + index).getBytes()),
                    "BRONCO", "Trim " + index, "https://ford.example/bronco/limit/" + index);
        }
        assertThat(reader.searchStructuredCatalog(CatalogFilters.empty(), 501)).hasSize(100);
    }

    @Test
    void fusesKeywordAndSemanticHitsAndPreservesSourceCitations() {
        var semanticOnly = UUID.fromString("45000000-0000-0000-0000-000000000001");
        var keywordOnly = UUID.fromString("45000000-0000-0000-0000-000000000002");
        var overlap = UUID.fromString("45000000-0000-0000-0000-000000000003");
        insertDocument(semanticOnly, "aventura versátil", vector(1), "https://ford.example/semantic",
                BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);
        insertDocument(keywordOnly, "ideal para familia", vector(2), "https://ford.example/keyword",
                MUSTANG_MODEL_ID, MUSTANG_MODEL_YEAR_ID, null, null);
        insertDocument(overlap, "SUV familiar para familia", vector(1), "https://ford.example/overlap",
                BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);

        var results = knowledgeReader.searchHybrid("familia", CatalogFilters.empty(), 8);

        assertThat(results).extracting(CatalogModelSearchResult::modelId)
                .contains(BRONCO_MODEL_ID, MUSTANG_MODEL_ID)
                .doesNotHaveDuplicates();
        assertThat(results).filteredOn(hit -> hit.modelId().equals(BRONCO_MODEL_ID))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.sources()).extracting(CatalogDocumentHit::documentId)
                            .containsExactlyInAnyOrder(semanticOnly, overlap);
                    assertThat(hit.sources()).extracting(CatalogDocumentHit::sourceUrl)
                            .contains("https://ford.example/overlap", "https://ford.example/semantic");
                });
    }

    @Test
    void findsModelLevelDocumentWithoutVariantLinkWhenModelIsFiltered() {
        var modelDocument = UUID.fromString("45000000-0000-0000-0000-000000000021");
        insertDocument(modelDocument, "SUV familiar para familia", vector(1),
                "https://ford.example/bronco/model", BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);

        var results = knowledgeReader.searchHybrid("familia", new CatalogFilters(
                "bronco", null, null, null, null, null, null, null), 8);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.modelId()).isEqualTo(BRONCO_MODEL_ID);
            assertThat(result.sources()).singleElement()
                    .extracting(CatalogDocumentHit::documentId).isEqualTo(modelDocument);
        });
    }

    @Test
    void groupsSourceChunksByModelAndReturnsExactMatchingTrimFacts() {
        var broncoChunk = UUID.fromString("45000000-0000-0000-0000-000000000031");
        var broncoSecondChunk = UUID.fromString("45000000-0000-0000-0000-000000000032");
        insertDocument(broncoChunk, "SUV familiar para familia", vector(1),
                "https://ford.example/bronco/family", BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);
        insertDocument(broncoSecondChunk, "espacio para familia y equipaje", vector(1),
                "https://ford.example/bronco/space", BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);
        insertVariant(UUID.fromString("25000000-0000-0000-0000-000000000003"),
                "BRONCO", "Outer Banks", "https://ford.example/bronco/outer-banks");
        jdbc.sql("update ford_catalog.vehicle_variants_v2 set price = 2000000 where variant_id = :id")
                .param("id", UUID.fromString("25000000-0000-0000-0000-000000000003")).update();

        var results = knowledgeReader.searchHybrid("familia", new CatalogFilters(
                "bronco", 2026, new BigDecimal("900000"), new BigDecimal("1500000"),
                "azul relámpago", null, null, null), 8);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.modelId()).isEqualTo(BRONCO_MODEL_ID);
            assertThat(result.model()).isEqualTo("BRONCO");
            assertThat(result.matchingVariants()).singleElement().satisfies(variant -> {
                assertThat(variant.variantId()).isEqualTo(BRONCO_VARIANT_ID);
                assertThat(variant.price()).isEqualByComparingTo("1000000.00");
                assertThat(variant.color()).isEqualTo("Azul relámpago");
                assertThat(variant.sourceUrl()).isNotBlank();
            });
            assertThat(result.sources()).hasSizeBetween(1, 2).allSatisfy(source -> {
                assertThat(source.documentId()).isIn(broncoChunk, broncoSecondChunk);
                assertThat(source.sourceUrl()).isIn(
                        "https://ford.example/bronco/family", "https://ford.example/bronco/space");
            });
        });
    }

    @Test
    void resolvesOfferOnlyDocumentToItsExactModelAndYear() {
        var offerId = UUID.fromString("35000000-0000-0000-0000-000000000001");
        var modelDocument = UUID.fromString("45000000-0000-0000-0000-000000000041");
        var offerDocument = UUID.fromString("45000000-0000-0000-0000-000000000042");
        insertDocument(modelDocument, "Manual técnico Bronco", vector(2),
                "https://ford.example/bronco/manual", BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);
        insertDocument(offerDocument, "Bono especial para familia", vector(1),
                "https://ford.example/bronco/offer", null, null, null, offerId);

        var results = knowledgeReader.searchHybrid("bono", new CatalogFilters(
                "bronco", 2026, null, null, null, null, null, null), 8);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.modelId()).isEqualTo(BRONCO_MODEL_ID);
            assertThat(result.sources()).singleElement().satisfies(source -> {
                assertThat(source.documentId()).isEqualTo(offerDocument);
                assertThat(source.offerId()).isEqualTo(offerId);
                assertThat(source.sourceUrl()).isEqualTo("https://ford.example/bronco/offer");
            });
        });
    }

    @Test
    void candidateBoundCountsModelsSoManyChunksDoNotCrowdOutAnotherModel() {
        for (int index = 0; index < 25; index++) {
            insertDocument(UUID.nameUUIDFromBytes(("bronco-chunk-" + index).getBytes()),
                    "manual familiar Bronco " + index, vector(1),
                    "https://ford.example/bronco/chunk/" + index,
                    BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);
        }
        insertDocument(UUID.fromString("55000000-0000-0000-0000-000000000001"),
                "aventura independiente", vector(2), "https://ford.example/mustang/semantic",
                MUSTANG_MODEL_ID, MUSTANG_MODEL_YEAR_ID, null, null);

        var results = knowledgeReader.searchHybrid("familia", CatalogFilters.empty(), 8);

        assertThat(results).extracting(CatalogModelSearchResult::modelId)
                .contains(BRONCO_MODEL_ID, MUSTANG_MODEL_ID)
                .doesNotHaveDuplicates();
    }

    @Test
    void runsHybridSearchWithOneCatalogDatabaseQuery() {
        insertDocument(UUID.fromString("45000000-0000-0000-0000-000000000011"),
                "SUV familiar para familia", vector(1), "https://ford.example/one-query");
        var preparedStatements = new AtomicInteger();
        var countingJdbc = JdbcClient.create(new CountingDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=ford_catalog",
                        POSTGRES.getUsername(), POSTGRES.getPassword()), preparedStatements));
        var countingReader = new JdbcFordCatalogKnowledgeReader(countingJdbc, text -> vectorValues(1));

        var results = countingReader.searchHybrid("familia", CatalogFilters.empty(), 8);

        assertThat(results).hasSize(1);
        assertThat(preparedStatements).hasValue(1);
    }

    @Test
    void rejectsInvalidVectorsAndEmptyQueriesBeforeCatalogDatabaseQuery() {
        var preparedStatements = new AtomicInteger();
        var countingJdbc = JdbcClient.create(new CountingDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=ford_catalog",
                        POSTGRES.getUsername(), POSTGRES.getPassword()), preparedStatements));
        var wrongDimensionReader = new JdbcFordCatalogKnowledgeReader(countingJdbc, text -> new float[2]);

        assertThatThrownBy(() -> wrongDimensionReader.searchHybrid("familia", CatalogFilters.empty(), 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1024 dimensions");
        assertThat(preparedStatements).hasValue(0);

        var nonFinite = new float[1024];
        nonFinite[0] = Float.NaN;
        var nonFiniteReader = new JdbcFordCatalogKnowledgeReader(countingJdbc, text -> nonFinite);
        assertThatThrownBy(() -> nonFiniteReader.searchHybrid("familia", CatalogFilters.empty(), 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite values");
        assertThat(preparedStatements).hasValue(0);

        var unusedEmbeddingReader = new JdbcFordCatalogKnowledgeReader(countingJdbc,
                text -> { throw new AssertionError("blank query must not be embedded"); });
        assertThat(unusedEmbeddingReader.searchHybrid(" ", CatalogFilters.empty(), 8)).isEmpty();
        assertThat(unusedEmbeddingReader.searchHybrid("familia", CatalogFilters.empty(), 0)).isEmpty();
        assertThat(unusedEmbeddingReader.searchHybrid("familia", CatalogFilters.empty(), -1)).isEmpty();
        assertThat(preparedStatements).hasValue(0);
    }

    @Test
    void readerRoleCanReadCatalogButCannotWriteOrReadPrivateSchemas() {
        var readerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "ford_catalog_reader", "integration-test-only");
        var readerJdbc = JdbcClient.create(readerDataSource);

        assertThat(readerJdbc.sql("select count(*) from ford_catalog.vehicle_search_v1")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(new JdbcFordCatalogReader(readerJdbc)
                .searchStructuredCatalog(CatalogFilters.empty(), 10))
                .extracting(result -> result.model())
                .containsExactly("BRONCO", "MUSTANG");
        assertThatThrownBy(() -> readerJdbc.sql("insert into ford_catalog.vehicle_search_v1 (model_year_id, model, normalized_name, year) values (gen_random_uuid(), 'TEST', 'test', 2026)").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> readerJdbc.sql("select * from ford_catalog.retrieval_document_manifest").query(String.class).list())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> readerJdbc.sql("select * from ford_metadata.private_notes").query(String.class).list())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
    }

    private static void insertVariant(UUID id, String model, String trim, String sourceUrl) {
        jdbc.sql("insert into ford_catalog.vehicle_variants_v2 (variant_id, model_year_id, model, normalized_model, year, trim, normalized_trim, price, currency, model_page_url, price_source_url, observed_at, comparison_url) values (:id, :modelYear, :model, lower(:model), 2026, :trim, lower(:trim), 1000000, 'MXN', :source, :source, current_timestamp, :source)")
                .param("id", id).param("modelYear", "BRONCO".equals(model) ? MODEL_YEAR_ID : MUSTANG_MODEL_YEAR_ID).param("model", model)
                .param("trim", trim).param("source", sourceUrl).update();
    }

    private static void insertDocument(UUID id, String content, String embedding, String sourceUrl) {
        insertDocument(id, content, embedding, sourceUrl, BRONCO_MODEL_ID, MODEL_YEAR_ID, null, null);
    }

    private static void insertDocument(UUID id, String content, String embedding, String sourceUrl,
                                       UUID modelId, UUID modelYearId, UUID variantId, UUID offerId) {
        jdbc.sql("insert into ford_catalog.catalog_documents_v1 (document_id, document_type, document_key, title, semantic_content, model_id, model_year_id, variant_id, offer_id, source_url, observed_at, embedding_model, embedding_status, embedding, search_vector) values (:id, 'vehicle', :idText, :title, :content, cast(:modelId as uuid), cast(:modelYearId as uuid), cast(:variantId as uuid), cast(:offerId as uuid), :source, current_timestamp, 'bge-m3', 'ready', cast(:embedding as vector), to_tsvector('spanish', :content))")
                .param("id", id).param("idText", id.toString()).param("title", content)
                .param("content", content).param("modelId", modelId).param("modelYearId", modelYearId)
                .param("variantId", variantId).param("offerId", offerId)
                .param("source", sourceUrl).param("embedding", embedding).update();
    }

    private static String vector(int activeDimension) {
        var values = new StringBuilder("[");
        for (int dimension = 1; dimension <= 1024; dimension++) {
            if (dimension > 1) {
                values.append(',');
            }
            values.append(dimension == activeDimension ? '1' : '0');
        }
        return values.append(']').toString();
    }

    private static float[] vectorValues(int activeDimension) {
        var values = new float[1024];
        values[activeDimension - 1] = 1.0f;
        return values;
    }

    private static final class CountingDataSource extends AbstractDataSource {
        private final DriverManagerDataSource delegate;
        private final AtomicInteger preparedStatements;

        private CountingDataSource(DriverManagerDataSource delegate, AtomicInteger preparedStatements) {
            this.delegate = delegate;
            this.preparedStatements = preparedStatements;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return count(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return count(delegate.getConnection(username, password));
        }

        private Connection count(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName())) {
                            preparedStatements.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }
    }
}
