package com.ford.service.conversations;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.catalog.adapter.out.JdbcCatalogAdapter;
import com.ford.service.catalog.CatalogCoverageAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LocalCatalogSnapshotLoaderTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    @TempDir Path tempDir;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Each test uses a disposable Testcontainers database, never the local application database.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists ford_read cascade");
            dropReaderRoleIfPresent(connection);
        }
    }

    @Test
    void importsAValidatedSnapshotIntoOnlyTheCatalogReadSchema() throws Exception {
        writeSnapshot(tempDir);

        var summary = LocalCatalogSnapshotLoader.load(tempDir, dataSource);

        assertThat(summary.rowCounts()).containsEntry("vehicle-search.csv", 1)
                .containsEntry("vehicle-variants.csv", 1)
                .containsEntry("vehicle-facts.csv", 1)
                .containsEntry("comparison-facts.csv", 1)
                .containsEntry("promotions.csv", 1)
                .containsEntry("product-vectors.csv", 1);
        assertThat(dataSource.getConnection().createStatement()
                .executeQuery("select count(*) from ford_read.vehicle_search_v1").next()).isTrue();
        assertThat(dataSource.getConnection().createStatement()
                .executeQuery("select count(*) from ford_read.catalog_documents_v1 where vector_dims(embedding)=1024")
                .next()).isTrue();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select to_regclass('ford_read.vehicle_images_v1') is not null "
                     + "and to_regclass('ford_read.vehicle_facts_v1') is not null")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).as("adapter's optional catalog relations exist").isTrue();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FORD_VALIDATE_LOCAL_CATALOG_EXPORT_DIR", matches = ".+")
    void validatesConfiguredCandidateExportInDisposablePostgres() {
        var directory = Path.of(System.getenv("FORD_VALIDATE_LOCAL_CATALOG_EXPORT_DIR"));

        var summary = LocalCatalogSnapshotLoader.load(directory, dataSource);

        assertThat(summary.rowCounts()).containsEntry("vehicle-search.csv", 39)
                .containsEntry("vehicle-variants.csv", 121)
                .containsEntry("vehicle-facts.csv", 4887)
                .containsEntry("comparison-facts.csv", 2447)
                .containsEntry("promotions.csv", 10)
                .containsEntry("product-vectors.csv", 1882);
        assertThat(summary.sha256()).matches("(?i)[a-f0-9]{64}");

        var catalog = new JdbcCatalogAdapter(JdbcClient.create(dataSource), new ObjectMapper());
        var report = new CatalogCoverageAudit(catalog, LocalDate.now(java.time.ZoneOffset.UTC)).audit(
                new CatalogCoverageAudit.SnapshotMetadata("oct25-candidate", summary.sha256(),
                        summary.refreshedAt(), summary.rowCounts()));
        assertThat(report.officialSourceScope()).isEqualTo("UNVERIFIED");
        assertThat(report.sourceSyncStatus()).isEqualTo("NOT_RECORDED");
        assertThat(report.modelYearTrimCount()).isEqualTo(121);
        assertThat(report.modelYearWithoutTrimCount()).isEqualTo(39);
        assertThat(report.sourcedFactCount()).isEqualTo(4887);
        assertThat(report.comparisonFactCount()).isEqualTo(2447);
        assertThat(report.promotionCount()).isEqualTo(10);
        assertThat(report.catalogStatus().embeddedDocumentCount()).isEqualTo(1882);
        assertThat(report.unknownPackageIdentityCount()).isEqualTo(121);
        var artifact = CatalogCoverageAudit.writeJson(report, Path.of(".").toAbsolutePath());
        assertThat(artifact).isEqualTo(Path.of("build/reports/catalog-coverage/catalog-coverage-oct25-candidate.json")
                .toAbsolutePath().normalize());
    }

    @Test
    void localSnapshotSupportsCatalogStatusPageLinksAndDocumentProvenance() throws Exception {
        writeSnapshot(tempDir);
        LocalCatalogSnapshotLoader.load(tempDir, dataSource);
        var catalog = new JdbcCatalogAdapter(JdbcClient.create(dataSource), new ObjectMapper());

        assertThat(catalog.status().latestSyncStatus()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(catalog.pageLinks("https://catalog.synthetic.invalid/ranger/", 0, 10, false))
                .satisfies(page -> {
                    assertThat(page.knownSource()).isTrue();
                    assertThat(page.links()).isEmpty();
                });
        assertThat(catalog.provenance("44444444-4444-4444-8444-444444444444"))
                .hasValueSatisfying(document -> {
                    assertThat(document.title()).isEqualTo("Ranger profile");
                    assertThat(document.documentType()).isEqualTo("vehicle_profile");
                    assertThat(document.sourceUrl()).isEqualTo("https://catalog.synthetic.invalid/ranger/");
                    assertThat(document.observedAt()).isNotNull();
                    assertThat(document.contentHash()).isNull();
                    assertThat(document.pageContentHash()).isNull();
                });
    }

    @Test
    void allowsValidSearchModelYearRowsWithoutTrimVariants() throws Exception {
        writeSnapshot(tempDir);
        writeCsv(tempDir, "vehicle-search.csv", HEADERS.get("vehicle-search.csv"),
                csvRow("11111111-1111-4111-8111-111111111111", "Ranger", "ranger", "2027", "907000", "MXN", "Pickup", "Pickup", "Gasolina", "https://catalog.synthetic.invalid/ranger/", "https://catalog.synthetic.invalid/ranger/price/", "2026-10-16T10:00:00Z"),
                csvRow("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Mustang RTR", "mustang-rtr", "2026", "950000", "MXN", "Car", "Coupe", "Gasolina", "https://catalog.synthetic.invalid/mustang-rtr/", "https://catalog.synthetic.invalid/mustang-rtr/price/", "2026-10-16T10:00:00Z"));
        refreshManifest(tempDir);

        var summary = LocalCatalogSnapshotLoader.load(tempDir, dataSource);

        assertThat(summary.rowCounts()).containsEntry("vehicle-search.csv", 2);
    }

    @Test
    void rejectsChangedCsvBeforeImport() throws Exception {
        writeSnapshot(tempDir);
        Files.writeString(tempDir.resolve("promotions.csv"), "tampered\n");

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash");
    }

    @Test
    void rejectsAnExistingCatalogSchemaWithoutChangingIt() throws Exception {
        writeSnapshot(tempDir);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema ford_read");
            statement.execute("create table ford_read.preservation_marker(value text)");
            statement.execute("insert into ford_read.preservation_marker values ('keep')");
        }

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog schema already exists");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select value from ford_read.preservation_marker")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("keep");
        }
    }

    @Test
    void rejectsInvalidSnapshotBeforeCreatingCatalogSchema() throws Exception {
        writeSnapshot(tempDir);
        Files.writeString(tempDir.resolve("promotions.csv"), "tampered\n");

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select to_regnamespace('ford_read') is null")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isTrue();
        }
    }

    @Test
    void grantsReaderOnlyApprovedCatalogReads() throws Exception {
        writeSnapshot(tempDir);
        markSnapshotAsApprovedSource(tempDir);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create role ford_catalog_test_reader login password 'reader-secret'");
        }

        try {
            LocalCatalogSnapshotLoader.load(tempDir, dataSource, "ford_catalog_test_reader");
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("create table ford_read.private_marker(value text)");
            }
            var reader = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    "ford_catalog_test_reader", "reader-secret");
            try (var connection = reader.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("select count(*) from ford_read.vehicle_search_v1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
            try (var connection = reader.getConnection(); var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "insert into ford_read.vehicle_search_v1(model) values ('blocked')"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update ford_read.vehicle_search_v1 set model='blocked'"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "delete from ford_read.vehicle_search_v1"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("create table ford_read.reader_ddl(value text)"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("create temporary table reader_temp(value text)"))
                        .isInstanceOf(java.sql.SQLException.class);
                try (var result = statement.executeQuery("select has_table_privilege(current_user, "
                        + "'ford_read.private_marker', 'SELECT')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                }
            }
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema if exists ford_read cascade");
                dropReaderRoleIfPresent(connection);
            }
        }
    }

    @Test
    void rejectsMissingRequiredCsvBeforeImport() throws Exception {
        writeSnapshot(tempDir);
        Files.delete(tempDir.resolve("vehicle-facts.csv"));

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vehicle-facts.csv");
    }

    @Test
    void rejectsProhibitedDocumentTypeBeforeImport() throws Exception {
        writeSnapshot(tempDir);
        writeSnapshot(tempDir, "dealer_information");

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document type");
    }

    @Test
    void rejectsWrongVectorModelOrDimensionBeforeImport() throws Exception {
        writeSnapshot(tempDir, "vehicle_profile", "nomic-embed-text", vector(3));

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("import failed validation")
                .satisfies(failure -> assertThat(failure.getCause())
                        .hasMessageContaining("expected 1024 dimensions, not 3"));
    }

    @Test
    void rejectsMissingEvidenceAndEmptyPromotionsBeforeImport() throws Exception {
        writeSnapshot(tempDir, "vehicle_profile", "bge-m3", vector(1024), "", "2026-10-16T10:00:00Z");

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source evidence");

        writeSnapshot(tempDir);
        writeCsv(tempDir, "promotions.csv", HEADERS.get("promotions.csv"));
        refreshManifest(tempDir);

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsNullDocumentTypeBeforeImport() throws Exception {
        writeSnapshot(tempDir);
        replaceProductVector(tempDir, "", "Ranger description", "bge-m3", "ready");

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document type");
    }

    @Test
    void rejectsNullEmbeddingMetadataAndContentBeforeImport() throws Exception {
        writeSnapshot(tempDir);
        replaceProductVector(tempDir, "vehicle_profile", "", "", "");

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid product embedding");
    }

    @Test
    void rejectsNullOfferTypeBeforeImport() throws Exception {
        writeSnapshot(tempDir);
        var promotions = tempDir.resolve("promotions.csv");
        var contents = Files.readString(promotions);
        assertThat(contents).contains("Synthetic conditions,promotion,Synthetic provider");
        Files.writeString(promotions, contents.replace(
                "Synthetic conditions,promotion,Synthetic provider",
                "Synthetic conditions,,Synthetic provider"));
        refreshManifest(tempDir);

        assertThatThrownBy(() -> LocalCatalogSnapshotLoader.load(tempDir, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed type");
    }

    @Test
    void reportsCommittedImportAsSuccessIfConnectionCleanupFailsAfterCommit() throws Exception {
        writeSnapshot(tempDir);

        var summary = LocalCatalogSnapshotLoader.load(tempDir, failAutoCommitRestoreOnce(dataSource));

        assertThat(summary.rowCounts()).containsEntry("vehicle-search.csv", 1);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from ford_read.vehicle_search_v1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1);
        }
    }

    static void writeSnapshot(Path directory) throws Exception {
        writeSnapshot(directory, "vehicle_profile", "bge-m3", vector(1024),
                "https://catalog.synthetic.invalid/ranger/", "2026-10-16T10:00:00Z");
    }

    static void writeSnapshot(Path directory, String documentType) throws Exception {
        writeSnapshot(directory, documentType, "bge-m3", vector(1024),
                "https://catalog.synthetic.invalid/ranger/", "2026-10-16T10:00:00Z");
    }

    private static void writeSnapshot(Path directory, String documentType, String embeddingModel,
                                      String embedding) throws Exception {
        writeSnapshot(directory, documentType, embeddingModel, embedding,
                "https://catalog.synthetic.invalid/ranger/", "2026-10-16T10:00:00Z");
    }

    private static void writeSnapshot(Path directory, String documentType, String embeddingModel,
                                      String embedding, String sourceUrl, String observedAt) throws Exception {
        Files.createDirectories(directory);
        writeCsv(directory, "vehicle-search.csv", HEADERS.get("vehicle-search.csv"),
                "11111111-1111-4111-8111-111111111111,Ranger,ranger,2027,907000,MXN,Pickup,Pickup,Gasolina,https://catalog.synthetic.invalid/ranger/,https://catalog.synthetic.invalid/ranger/price/,2026-10-16T10:00:00Z");
        writeCsv(directory, "vehicle-variants.csv", HEADERS.get("vehicle-variants.csv"),
                "22222222-2222-4222-8222-222222222222,11111111-1111-4111-8111-111111111111,Ranger,ranger,2027,XLT Gasolina 4x2,xltgasolina4x2,907000,MXN,https://catalog.synthetic.invalid/ranger/,https://catalog.synthetic.invalid/ranger/price/,2026-10-16T10:00:00Z,");
        writeCsv(directory, "vehicle-facts.csv", HEADERS.get("vehicle-facts.csv"),
                "22222222-2222-4222-8222-222222222222,11111111-1111-4111-8111-111111111111,Ranger,2027,XLT Gasolina 4x2,Motor,specification,Motor,motor,2.0L,,L,https://catalog.synthetic.invalid/ranger/specs/,2026-10-16T10:00:00Z,https://catalog.synthetic.invalid/ranger/");
        writeCsv(directory, "comparison-facts.csv", HEADERS.get("comparison-facts.csv"),
                "Ranger,2027,XLT Gasolina 4x2,Ford,Ranger,2027,Wildtrak,Powertrain,Motor,2.0L,L,https://catalog.synthetic.invalid/ranger/compare/,2026-10-16T10:00:00Z,https://catalog.synthetic.invalid/ranger/compare/");
        writeCsv(directory, "promotions.csv", HEADERS.get("promotions.csv"),
                "33333333-3333-4333-8333-333333333333,Ranger,2027,Synthetic promotion,Synthetic offer,15000,,907000,MXN,2026-10-01,2026-12-31,Synthetic conditions,promotion,Synthetic provider,250000,,12000,60,0,0.1125,,true,https://catalog.synthetic.invalid/ranger/offer/,2026-10-16T10:00:00Z,https://catalog.synthetic.invalid/ranger/,[],[],,,,");
        writeCsv(directory, "product-vectors.csv", HEADERS.get("product-vectors.csv"),
                csvRow("44444444-4444-4444-8444-444444444444", documentType, "ranger-profile", "Ranger profile",
                        "Descripción \"XLT, pickup familiar\"", "", "", "22222222-2222-4222-8222-222222222222", "",
                        sourceUrl, observedAt, embeddingModel, "ready", embedding));
        refreshManifest(directory);
    }

    private static void replaceProductVector(Path directory, String documentType, String semanticContent,
                                             String embeddingModel, String embeddingStatus) throws Exception {
        writeCsv(directory, "product-vectors.csv", HEADERS.get("product-vectors.csv"), csvRow(
                "44444444-4444-4444-8444-444444444444", documentType, "ranger-profile", "Ranger profile",
                semanticContent, "", "", "22222222-2222-4222-8222-222222222222", "",
                "https://catalog.synthetic.invalid/ranger/", "2026-10-16T10:00:00Z",
                embeddingModel, embeddingStatus, vector(1024)));
        refreshManifest(directory);
    }

    private static void writeCsv(Path directory, String filename, String header, String... rows) throws IOException {
        var content = new StringBuilder(header).append('\n');
        for (var row : rows) content.append(row).append('\n');
        Files.writeString(directory.resolve(filename), content);
    }

    private static void refreshManifest(Path directory) throws Exception {
        var entries = new ArrayList<Map<String, Object>>();
        for (var filename : HEADERS.keySet()) {
            var path = directory.resolve(filename);
            var lines = Files.readAllLines(path);
            entries.add(Map.of("name", filename, "rowCount", Math.max(0, lines.size() - 1),
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))));
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("sourceLabel", "test-only synthetic CSV");
        manifest.put("refreshedAt", "2026-10-16T10:00:00Z");
        manifest.put("files", entries);
        Files.writeString(directory.resolve("snapshot-manifest.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(manifest));
    }

    static void markSnapshotAsApprovedSource(Path directory) throws IOException {
        var manifest = directory.resolve("snapshot-manifest.json");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("test-only synthetic CSV", LocalCatalogSnapshotLoader.SOURCE_LABEL));
    }

    private static void dropReaderRoleIfPresent(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select exists(select 1 from pg_roles "
                     + "where rolname='ford_catalog_test_reader')")) {
            if (result.next() && result.getBoolean(1)) {
                statement.execute("drop owned by ford_catalog_test_reader");
                statement.execute("drop role ford_catalog_test_reader");
            }
        }
    }

    private static DataSource failAutoCommitRestoreOnce(DataSource delegate) {
        var failureScheduled = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    var value = invoke(delegate, method, args);
                    if (!"getConnection".equals(method.getName())) return value;
                    var committed = new AtomicBoolean(false);
                    var connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                if ("setAutoCommit".equals(connectionMethod.getName())
                                        && Boolean.TRUE.equals(connectionArgs[0]) && committed.get()
                                        && failureScheduled.compareAndSet(true, false)) {
                                    throw new SQLException("synthetic post-commit cleanup failure");
                                }
                                var result = invoke(connection, connectionMethod, connectionArgs);
                                if ("commit".equals(connectionMethod.getName())) committed.set(true);
                                return result;
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static String vector(int dimension) {
        return "[" + String.join(",", java.util.Collections.nCopies(dimension, "0.0")) + "]";
    }

    private static String csvRow(String... values) {
        return java.util.Arrays.stream(values).map(value -> value.isEmpty() ? ""
                        : "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static final Map<String, String> HEADERS = Map.of(
            "vehicle-search.csv", "model_year_id,model,normalized_name,year,price,currency,category,body_type,fuel_type,model_page_url,price_source_url,observed_at",
            "vehicle-variants.csv", "variant_id,model_year_id,model,normalized_model,year,trim,normalized_trim,price,currency,model_page_url,price_source_url,observed_at,comparison_url",
            "vehicle-facts.csv", "variant_id,model_year_id,model,year,trim,category,fact_type,fact_name,fact_name_normalized,fact_value,value_numeric,fact_unit,source_url,observed_at,model_page_url",
            "comparison-facts.csv", "model,year,trim,competitor_make,competitor_model,competitor_year,competitor_variant,category,fact_name,fact_value,fact_unit,source_url,observed_at,comparison_url",
            "promotions.csv", "promotion_id,model,year,title,description,discount_amount,discount_percent,cash_price,currency,valid_from,valid_until,conditions,offer_type,provider,down_payment_amount,down_payment_percent,monthly_payment,term_months,months_without_interest,annual_interest_rate,cat_percent,requires_credit_approval,source_url,observed_at,model_page_url,benefits,term_conflict,tax_rate_percent,opening_commission_rate_percent,maximum_down_payment_percent,life_insurance_price",
            "product-vectors.csv", "document_id,document_type,document_key,title,semantic_content,model_id,model_year_id,variant_id,offer_id,source_url,observed_at,embedding_model,embedding_status,embedding");
}
