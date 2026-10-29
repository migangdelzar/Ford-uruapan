package com.ford.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.adapter.out.JdbcCatalogAdapter;
import com.ford.service.catalog.api.CatalogQueryVector;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.database.FordPostgresContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CatalogSemanticRetrievalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    private static JdbcClient jdbc;
    private static JdbcCatalogAdapter catalog;
    private static UUID rangerScopeVariantId;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("create schema ford_read").update();
        jdbc.sql("create extension if not exists pg_trgm").update();
        jdbc.sql("""
                create table ford_read.vehicle_search_v1 (
                  model_year_id uuid, model varchar(100), normalized_name varchar(100), year integer,
                  price numeric(14,2), currency varchar(3), category varchar(100), body_type varchar(100),
                  fuel_type varchar(100), model_page_url varchar(500), price_source_url varchar(500),
                  observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.vehicle_variants_v1 (
                  variant_id uuid, model_year_id uuid, model varchar(100), normalized_model varchar(100),
                  year integer, trim varchar(100), normalized_trim varchar(100), price numeric(14,2),
                  currency varchar(3), model_page_url varchar(500), price_source_url varchar(500),
                  observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.vehicle_variants_v2 (
                  variant_id uuid, model_year_id uuid, model varchar(100), normalized_model varchar(100),
                  year integer, trim varchar(100), normalized_trim varchar(100), price numeric(14,2),
                  currency varchar(3), model_page_url varchar(500), price_source_url varchar(500),
                  observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.catalog_documents_v1 (
                  document_id uuid, document_key varchar(200), model_id uuid, model_year_id uuid,
                  variant_id uuid, offer_id uuid, document_type varchar(80), title varchar(200),
                  semantic_content text, search_vector tsvector, embedding vector(1024),
                  embedding_model varchar(100), embedding_status varchar(30), source_url varchar(500),
                  observed_at timestamptz)
                """).update();
        jdbc.sql("""
                create table ford_read.offers_v2 (
                  promotion_id uuid, model varchar(100), year integer, title varchar(200),
                  description varchar(500), offer_type varchar(50), provider varchar(100),
                  cash_price numeric(14,2), monthly_payment numeric(14,2),
                  down_payment_amount numeric(14,2), down_payment_percent numeric(7,2),
                  term_months integer, months_without_interest integer,
                  annual_interest_rate numeric(7,4), cat_percent numeric(7,4), currency varchar(3),
                  valid_from date, valid_until date, requires_credit_approval boolean, conditions text,
                  source_url varchar(500), model_page_url varchar(500), observed_at timestamptz,
                  benefits jsonb, term_conflict jsonb, tax_rate_percent numeric(7,4),
                  opening_commission_rate_percent numeric(7,4), maximum_down_payment_percent numeric(7,4),
                  life_insurance_price numeric(14,2))
                """).update();

        var rangerYear = UUID.randomUUID();
        var broncoYear = UUID.randomUUID();
        var rangerWithoutSourceYear = UUID.randomUUID();
        var rangerWrongModelVectorYear = UUID.randomUUID();
        var rangerScopeYear = UUID.randomUUID();
        var rangerVariant = UUID.randomUUID();
        rangerScopeVariantId = UUID.randomUUID();
        insertVehicle(rangerYear, "Ranger", "ranger", 2027, 899000);
        insertVehicle(broncoYear, "Bronco", "bronco", 2026, 1100000);
        insertVehicle(rangerWithoutSourceYear, "Ranger", "ranger", 2028, 950000);
        insertVehicle(rangerWrongModelVectorYear, "Ranger", "ranger", 2029, 990000);
        insertVehicle(rangerScopeYear, "Ranger", "ranger", 2030, 999000);
        jdbc.sql("""
                insert into ford_read.vehicle_variants_v1
                  (variant_id, model_year_id, model, normalized_model, year, trim, normalized_trim,
                   price, currency, model_page_url, price_source_url, observed_at)
                values (:variant, :yearId, 'Ranger', 'ranger', 2027, 'XLT Gasolina 4x2',
                        'xltgasolina4x2', 899000, 'MXN', 'https://www.ford.mx/vehiculos/ranger/',
                        'https://www.ford.mx/vehiculos/ranger/precio/', '2026-10-16T10:00:00Z')
                """).param("variant", rangerVariant).param("yearId", rangerYear).update();

        jdbc.sql("""
                insert into ford_read.vehicle_variants_v2
                  (variant_id, model_year_id, model, normalized_model, year, trim, normalized_trim,
                   price, currency, model_page_url, price_source_url, observed_at)
                values (:variant, :yearId, 'Ranger', 'ranger', 2030, 'XLT Gasolina 4x2',
                        'xltgasolina4x2', 875000, 'MXN', 'https://www.ford.mx/vehiculos/ranger/xlt/',
                        'https://www.ford.mx/vehiculos/ranger/xlt/precio/', '2026-10-18T10:00:00Z')
                """).param("variant", rangerScopeVariantId).param("yearId", rangerScopeYear).update();

        jdbc.sql("""
                insert into ford_read.vehicle_variants_v2
                  (variant_id, model_year_id, model, normalized_model, year, trim, normalized_trim,
                   price, currency, model_page_url, price_source_url, observed_at)
                values (:variant, :yearId, 'Ranger', 'ranger', 2027, 'XLT Gasolina 4x2',
                        'xltgasolina4x2', 899000, 'MXN', 'https://www.ford.mx/vehiculos/ranger/',
                        'https://www.ford.mx/vehiculos/ranger/precio/', '2026-10-16T10:00:00Z')
                """).param("variant", rangerVariant).param("yearId", rangerYear).update();

        insertDocument(rangerYear, rangerVariant, "Ranger 2027 valid BGE model pickup familiar",
                vectorLiteral(0, 1), "bge-m3", "ready", "https://www.ford.mx/vehiculos/ranger/");
        insertTypedDocument(rangerScopeYear, rangerScopeVariantId, "vehicle_profile", "Ranger pickup XLT perfil técnico",
                vectorLiteral(0, 1), "https://www.ford.mx/vehiculos/ranger/xlt/");
        insertTypedDocument(rangerScopeYear, null, "technology", "Ranger pickup XLT referencia general del modelo",
                vectorLiteral(1, 0), "https://www.ford.mx/vehiculos/ranger/tecnologia/");
        insertTypedDocument(rangerScopeYear, null, "vehicle_comparison", "Ranger pickup XLT comparación competidor",
                vectorLiteral(1, 0), "https://www.ford.mx/vehiculos/ranger/comparativo/");
        insertDocument(rangerYear, rangerVariant, "wrong model tag exact vector match",
                vectorLiteral(1, 0), "nomic-embed-text", "ready", "https://www.ford.mx/vehiculos/ranger/");
        insertDocument(rangerYear, rangerVariant, "Ranger 2027 camioneta XLT",
                null, "bge-m3", "pending", "https://www.ford.mx/vehiculos/ranger/");
        insertDocument(broncoYear, null, "Ranger pickup familia; Bronco SUV",
                vectorLiteral(0, 1), "bge-m3", "ready", "https://www.ford.mx/vehiculos/bronco/");
        insertDocument(rangerWithoutSourceYear, null, "Ranger 2028 pickup familiar",
                vectorLiteral(1, 0), "bge-m3", "ready", null);
        insertDocument(rangerWrongModelVectorYear, null, "Ranger 2029 pickup familiar",
                vectorLiteral(1, 0), "nomic-embed-text", "ready", "https://www.ford.mx/vehiculos/ranger/");
        insertOffer(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), rangerYear,
                "Plan Ranger financiamiento mensualidad", "2026-10-31", vectorLiteral(1, 0));
        insertOffer(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), rangerYear,
                "Plan Ranger financiamiento vencido", "2026-09-30", vectorLiteral(1, 0));

        catalog = new JdbcCatalogAdapter(jdbc, new ObjectMapper());
    }

    @Test
    void filtersSelectorsAndSourceEvidenceBeforeFullTextTrigramAndVectorRanking() {
        var query = new VehicleSearchQuery("Ranger pickup", "Ranger", 2027,
                null, null, null, null, null, 5);
        var candidates = catalog.searchCandidates(query, queryVector());

        assertThat(candidates.semanticSearch()).isEqualTo("ready");
        assertThat(candidates.fullText()).isNotEmpty();
        assertThat(candidates.trigram()).isNotEmpty();
        assertThat(candidates.vector()).singleElement().satisfies(candidate -> {
            assertThat(candidate.stableId()).startsWith("fixture:");
            assertThat(candidate.evidence().model()).isEqualTo("Ranger");
            assertThat(candidate.evidence().year()).isEqualTo(2027);
            assertThat(candidate.evidence().price()).isEqualByComparingTo("899000");
            assertThat(candidate.searchText()).contains("valid BGE model").doesNotContain("wrong model tag");
        });
        for (var list : List.of(candidates.fullText(), candidates.trigram(), candidates.vector())) {
            assertThat(list).allSatisfy(candidate -> {
                assertThat(candidate.evidence().model()).isEqualTo("Ranger");
                assertThat(candidate.evidence().year()).isEqualTo(2027);
                assertThat(candidate.evidence().priceSourceUrl())
                        .isEqualTo("https://www.ford.mx/vehiculos/ranger/precio/");
                assertThat(candidate.evidence().year()).isEqualTo(2027);
            });
        }
    }

    @Test
    void preservesVariantTrimAndExcludesCompetitorComparisonsInEveryVehicleLane() {
        var query = new VehicleSearchQuery("Ranger pickup XLT", "Ranger", 2030,
                null, null, null, null, null, 5);
        var candidates = catalog.searchCandidates(query, queryVector());

        for (var list : List.of(candidates.fullText(), candidates.trigram(), candidates.vector())) {
            assertThat(list).noneSatisfy(candidate ->
                    assertThat(candidate.searchText()).contains("comparación competidor"));
            assertThat(list).anySatisfy(candidate -> {
                assertThat(candidate.evidence().trim()).isEqualTo("XLT Gasolina 4x2");
                assertThat(candidate.evidence().variantId()).isEqualTo(rangerScopeVariantId.toString());
            });
            assertThat(list).anySatisfy(candidate -> {
                assertThat(candidate.evidence().trim()).isNull();
                assertThat(candidate.evidence().variantId()).isNull();
                assertThat(candidate.searchText()).contains("referencia general");
            });
        }
        assertThat(candidates.fullText()).anySatisfy(candidate -> {
            assertThat(candidate.evidence().trim()).isEqualTo("XLT Gasolina 4x2");
            assertThat(candidate.evidence().variantId()).isEqualTo(rangerScopeVariantId.toString());
            assertThat(candidate.evidence().price()).isEqualByComparingTo("875000");
            assertThat(candidate.evidence().priceSourceUrl())
                    .isEqualTo("https://www.ford.mx/vehiculos/ranger/xlt/precio/");
            assertThat(candidate.evidence().retrievedEvidence()).singleElement().satisfies(source -> {
                assertThat(source.documentType()).isEqualTo("vehicle_profile");
                assertThat(source.sourceUrl()).isEqualTo("https://www.ford.mx/vehiculos/ranger/xlt/");
                assertThat(source.trim()).isEqualTo("XLT Gasolina 4x2");
            });
            assertThat(candidate.searchText()).contains("perfil técnico");
        });
        assertThat(candidates.fullText()).anySatisfy(candidate -> {
            assertThat(candidate.evidence().trim()).isNull();
            assertThat(candidate.evidence().variantId()).isNull();
            assertThat(candidate.evidence().price()).isEqualByComparingTo("999000");
            assertThat(candidate.evidence().priceSourceUrl())
                    .isEqualTo("https://www.ford.mx/vehiculos/ranger/precio/");
            assertThat(candidate.evidence().retrievedEvidence()).singleElement()
                    .satisfies(source -> assertThat(source.trim()).isNull());
            assertThat(candidate.searchText()).contains("referencia general");
        });

        var priceFiltered = catalog.searchCandidates(new VehicleSearchQuery("Ranger pickup XLT", "Ranger", 2030,
                new java.math.BigDecimal("900000"), null, null, null, null, 5), queryVector());
        assertThat(priceFiltered.fullText()).noneSatisfy(candidate ->
                assertThat(candidate.evidence().variantId()).isEqualTo(rangerScopeVariantId.toString()));
        assertThat(priceFiltered.fullText()).anySatisfy(candidate ->
                assertThat(candidate.evidence().price()).isEqualByComparingTo("999000"));
    }

    @Test
    void filtersOfferTypeModelYearAndValidityBeforeSemanticRanking() {
        var query = new OfferQuery("financiamiento Ranger", "Ranger", 2027, "financing", null,
                null, null, null, null, 60, true, java.time.LocalDate.parse("2026-10-19"), 3);
        var candidates = catalog.offerCandidates(query, queryVector());

        assertThat(candidates.semanticSearch()).isEqualTo("ready");
        assertThat(candidates.fullText()).extracting(candidate -> candidate.evidence().offerId())
                .containsExactly("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(candidates.trigram()).extracting(candidate -> candidate.evidence().offerId())
                .containsExactly("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(candidates.vector()).extracting(candidate -> candidate.evidence().offerId())
                .containsExactly("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(candidates.vector().getFirst().stableId()).startsWith("fixture:offer:");
        assertThat(candidates.vector().getFirst().evidence().monthlyPayment())
                .isEqualByComparingTo("12000");
    }

    @Test
    void reportsSemanticUnavailableWhenOnlyWrongModelVectorsMatchTheSelectors() {
        var query = new VehicleSearchQuery("Ranger pickup", "Ranger", 2029,
                null, null, null, null, null, 5);
        var candidates = catalog.searchCandidates(query, queryVector());

        assertThat(candidates.fullText()).isNotEmpty();
        assertThat(candidates.semanticSearch()).isEqualTo("unavailable");
        assertThat(candidates.vector()).isEmpty();
    }

    @Test
    void excludesDocumentsWithoutObservedSourceEvidence() {
        var query = new VehicleSearchQuery("Ranger pickup", "Ranger", 2028,
                null, null, null, null, null, 5);
        var candidates = catalog.searchCandidates(query, queryVector());

        assertThat(candidates.semanticSearch()).isEqualTo("unavailable");
        assertThat(candidates.fullText()).isEmpty();
        assertThat(candidates.trigram()).isEmpty();
        assertThat(candidates.vector()).isEmpty();
    }

    private static void insertVehicle(UUID modelYearId, String model, String normalizedModel,
                                      int year, int price) {
        jdbc.sql("""
                insert into ford_read.vehicle_search_v1
                  (model_year_id, model, normalized_name, year, price, currency,
                   model_page_url, price_source_url, observed_at)
                values (:id, :model, :normalized, :year, :price, 'MXN',
                        :modelUrl, :priceUrl, '2026-10-16T10:00:00Z')
                """)
                .param("id", modelYearId)
                .param("model", model)
                .param("normalized", normalizedModel)
                .param("year", year)
                .param("price", price)
                .param("modelUrl", "https://www.ford.mx/vehiculos/" + normalizedModel + "/")
                .param("priceUrl", "https://www.ford.mx/vehiculos/" + normalizedModel + "/precio/")
                .update();
    }

    private static void insertDocument(UUID modelYearId, UUID variantId, String text,
                                       String embedding, String model, String status, String sourceUrl) {
        insertTypedDocument(modelYearId, variantId, "vehicle_profile", text, embedding, model, status, sourceUrl);
    }

    private static void insertTypedDocument(UUID modelYearId, UUID variantId, String documentType, String text,
                                            String embedding, String sourceUrl) {
        insertTypedDocument(modelYearId, variantId, documentType, text, embedding, "bge-m3", "ready", sourceUrl);
    }

    private static void insertTypedDocument(UUID modelYearId, UUID variantId, String documentType, String text,
                                            String embedding, String model, String status, String sourceUrl) {
        jdbc.sql("""
                insert into ford_read.catalog_documents_v1
                  (document_id, document_key, model_year_id, variant_id, document_type, title,
                   semantic_content, search_vector, embedding, embedding_model, embedding_status,
                   source_url, observed_at)
                values (:documentId, :documentKey, :modelYearId, :variantId, :documentType,
                        'Ford vehicle profile', :content, to_tsvector('spanish', :content),
                        cast(:embedding as vector), :embeddingModel, :embeddingStatus, :sourceUrl,
                        case when cast(:sourceUrl as varchar) is null then null
                             else '2026-10-16T10:00:00Z'::timestamptz end)
                """)
                .param("documentId", UUID.randomUUID())
                .param("documentKey", "fixture:" + UUID.randomUUID())
                .param("modelYearId", modelYearId)
                .param("variantId", variantId)
                .param("documentType", documentType)
                .param("content", text)
                .param("embedding", embedding)
                .param("embeddingModel", model)
                .param("embeddingStatus", status)
                .param("sourceUrl", sourceUrl)
                .update();
    }

    private static void insertOffer(UUID offerId, UUID modelYearId, String title,
                                    String validUntil, String embedding) {
        var sourceUrl = "https://www.ford.mx/credito/ranger/";
        var modelUrl = "https://www.ford.mx/vehiculos/ranger/";
        jdbc.sql("""
                insert into ford_read.offers_v2
                  (promotion_id, model, year, title, description, offer_type, provider,
                   monthly_payment, term_months, currency, valid_from, valid_until,
                   requires_credit_approval, conditions, source_url, model_page_url,
                   observed_at, benefits, tax_rate_percent)
                values (:id, 'Ranger', 2027, :title, :description, 'financing', 'Ford Credit',
                        12000, 60, 'MXN', '2026-10-01', cast(:validUntil as date), true,
                        'Sujeto a aprobación', :sourceUrl, :modelUrl,
                        '2026-10-16T10:00:00Z', '["Mensualidad fija"]'::jsonb, 16)
                """)
                .param("id", offerId)
                .param("title", title)
                .param("description", "Plan de financiamiento Ranger con mensualidad fija")
                .param("validUntil", validUntil)
                .param("sourceUrl", sourceUrl)
                .param("modelUrl", modelUrl)
                .update();
        insertOfferDocument(offerId, modelYearId, title, embedding, sourceUrl);
    }

    private static void insertOfferDocument(UUID offerId, UUID modelYearId, String title,
                                           String embedding, String sourceUrl) {
        jdbc.sql("""
                insert into ford_read.catalog_documents_v1
                  (document_id, document_key, model_year_id, offer_id, document_type, title,
                   semantic_content, search_vector, embedding, embedding_model, embedding_status,
                   source_url, observed_at)
                values (:documentId, :documentKey, :modelYearId, :offerId, 'offer', :title,
                        :content, to_tsvector('spanish', :content), cast(:embedding as vector),
                        'bge-m3', 'ready', :sourceUrl, '2026-10-16T10:00:00Z')
                """)
                .param("documentId", UUID.randomUUID())
                .param("documentKey", "fixture:offer:" + offerId)
                .param("modelYearId", modelYearId)
                .param("offerId", offerId)
                .param("title", title)
                .param("content", title + " financiamiento mensualidad Ranger")
                .param("embedding", embedding)
                .param("sourceUrl", sourceUrl)
                .update();
    }

    private static String vectorLiteral(int first, int second) {
        var values = new ArrayList<Integer>(1024);
        values.add(first);
        values.add(second);
        while (values.size() < 1024) values.add(0);
        return "[" + String.join(",", values.stream().map(String::valueOf).toList()) + "]";
    }

    private static CatalogQueryVector queryVector() {
        var values = new ArrayList<Float>(1024);
        values.add(1.0f);
        values.add(0.0f);
        while (values.size() < 1024) values.add(0.0f);
        return new CatalogQueryVector("bge-m3", values);
    }
}
