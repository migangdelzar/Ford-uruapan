package com.ford.service.conversations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates and imports only approved, filtered catalog CSVs into a disposable PostgreSQL test database. */
final class LocalCatalogSnapshotLoader {
    static final String SOURCE_LABEL = "emme-2/ford_read filtered product views";
    static final List<String> FILE_NAMES = List.of("vehicle-search.csv", "vehicle-variants.csv",
            "vehicle-facts.csv", "comparison-facts.csv", "promotions.csv", "product-vectors.csv");
    static final Set<String> PRODUCT_TYPES = Set.of("overview", "pricing", "offer", "promotion",
            "vehicle_comparison", "vehicle_profile", "technology");
    private static final String MANIFEST_FILE = "snapshot-manifest.json";
    private static final List<String> READER_RELATIONS = List.of(
            "catalog_documents_v1", "vehicle_search_v1", "vehicle_variants_v2", "vehicle_facts_v2",
            "comparison_facts_v2", "vehicle_images_v1", "vehicle_facts_v1", "offers_v2",
            "promotions_v3", "page_links_v1", "catalog_pages_v1", "document_provenance_v1",
            "catalog_status_v1");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, CsvTable> TABLES = tables();

    private LocalCatalogSnapshotLoader() { }

    static SnapshotSummary load(Path exportDirectory, DataSource catalogDataSource) {
        return load(exportDirectory, catalogDataSource, null, true);
    }

    static SnapshotSummary load(Path exportDirectory, DataSource catalogDataSource, String readerRole) {
        if (readerRole == null || !readerRole.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw invalid("catalog reader role name is invalid");
        }
        return load(exportDirectory, catalogDataSource, readerRole, false);
    }

    static void validateApprovedSnapshot(Path exportDirectory) {
        Objects.requireNonNull(exportDirectory, "export directory is required");
        readAndValidateManifest(exportDirectory.toAbsolutePath().normalize(), false);
    }

    static String manifestSha256(Path exportDirectory) {
        Objects.requireNonNull(exportDirectory, "export directory is required");
        return readAndValidateManifest(exportDirectory.toAbsolutePath().normalize(), false).sha256();
    }

    private static SnapshotSummary load(Path exportDirectory, DataSource catalogDataSource,
                                        String readerRole, boolean allowSyntheticSource) {
        Objects.requireNonNull(exportDirectory, "export directory is required");
        Objects.requireNonNull(catalogDataSource, "catalog data source is required");
        var directory = exportDirectory.toAbsolutePath().normalize();
        var manifest = readAndValidateManifest(directory, allowSyntheticSource);
        SnapshotSummary committedImport = null;
        try (var connection = catalogDataSource.getConnection()) {
            var previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                createCatalogSchema(connection);
                var counts = new LinkedHashMap<String, Integer>();
                var copy = connection.unwrap(PGConnection.class).getCopyAPI();
                for (var fileName : FILE_NAMES) {
                    var table = TABLES.get(fileName);
                    try (var input = Files.newInputStream(directory.resolve(fileName))) {
                        var copied = copy.copyIn(table.copySql(), input);
                        if (copied != manifest.rowCounts().get(fileName)) {
                            throw invalid("row count does not match manifest for " + fileName);
                        }
                        counts.put(fileName, Math.toIntExact(copied));
                    }
                }
                validateImportedRows(connection, counts);
                if (readerRole != null) grantReaderAccess(connection, readerRole);
                var summary = new SnapshotSummary(manifest.refreshedAt(), Map.copyOf(counts), manifest.sha256());
                connection.commit();
                committedImport = summary;
                return summary;
            } catch (Exception failure) {
                rollbackQuietly(connection);
                if (failure instanceof IllegalArgumentException invalid) throw invalid;
                throw new IllegalArgumentException("filtered catalog snapshot import failed validation", failure);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Connection is closed immediately after this test-only import.
                }
            }
        } catch (SQLException failure) {
            if (committedImport != null) return committedImport;
            throw new IllegalArgumentException("filtered catalog snapshot could not be loaded", failure);
        }
    }

    private static Manifest readAndValidateManifest(Path directory, boolean allowSyntheticSource) {
        var manifestPath = directory.resolve(MANIFEST_FILE);
        if (!Files.isDirectory(directory) || !Files.isRegularFile(manifestPath)) {
            throw invalid("filtered catalog snapshot manifest is missing");
        }
        try {
            var bytes = Files.readAllBytes(manifestPath);
            var root = OBJECT_MAPPER.readTree(bytes);
            var sourceLabel = text(root, "sourceLabel");
            if (!SOURCE_LABEL.equals(sourceLabel)
                    && !(allowSyntheticSource && "test-only synthetic CSV".equals(sourceLabel))) {
                throw invalid("filtered catalog snapshot source label is not approved");
            }
            var refreshedAt = Instant.parse(text(root, "refreshedAt"));
            var files = root.path("files");
            if (!files.isArray() || files.size() != FILE_NAMES.size()) {
                throw invalid("filtered catalog snapshot manifest file list is invalid");
            }
            var rowCounts = new LinkedHashMap<String, Integer>();
            for (var entry : files) {
                var name = text(entry, "name");
                if (!FILE_NAMES.contains(name) || rowCounts.containsKey(name)) {
                    throw invalid("filtered catalog snapshot manifest contains an unsupported file");
                }
                var path = directory.resolve(name).normalize();
                if (!path.startsWith(directory) || !Files.isRegularFile(path)) {
                    throw invalid("required snapshot file is missing: " + name);
                }
                var expectedHash = text(entry, "sha256");
                var actualHash = sha256(Files.readAllBytes(path));
                if (!actualHash.equalsIgnoreCase(expectedHash)) {
                    throw invalid("snapshot file hash mismatch: " + name);
                }
                var expectedCount = entry.path("rowCount").asInt(-1);
                if (expectedCount < 1) throw invalid("required snapshot file is empty: " + name);
                verifyHeader(path, TABLES.get(name).header(), name);
                rowCounts.put(name, expectedCount);
            }
            if (!rowCounts.keySet().containsAll(FILE_NAMES)) {
                throw invalid("filtered catalog snapshot is missing required files");
            }
            return new Manifest(refreshedAt, Map.copyOf(rowCounts), sha256(bytes));
        } catch (IOException | java.time.DateTimeException failure) {
            throw new IllegalArgumentException("filtered catalog snapshot manifest is unreadable", failure);
        }
    }

    private static void validateImportedRows(Connection connection, Map<String, Integer> counts) throws SQLException {
        if (counts.values().stream().anyMatch(count -> count == 0)) {
            throw invalid("required catalog view export is empty");
        }
        requireZero(connection, "select count(*) from ford_read.catalog_documents_v1 where "
                + "document_type is null or document_type not in "
                + "('overview','pricing','offer','promotion','vehicle_comparison','vehicle_profile','technology')",
                "prohibited product document type");
        requireZero(connection, "select count(*) from ford_read.catalog_documents_v1 where "
                + "embedding_status is null or embedding_status <> 'ready' "
                + "or embedding_model is null or embedding_model <> 'bge-m3' or embedding is null "
                + "or vector_dims(embedding) <> 1024 or source_url is null or observed_at is null "
                + "or semantic_content is null or btrim(semantic_content) = ''",
                "invalid product embedding or source evidence");
        requireZero(connection, "select count(*) from ford_read.vehicle_search_v1 where "
                + "price_source_url is null or observed_at is null", "vehicle price lacks source evidence");
        requireZero(connection, "select count(*) from ford_read.vehicle_variants_v2 where "
                + "price_source_url is null or observed_at is null", "vehicle variant lacks source evidence");
        requireZero(connection, "select count(*) from ford_read.vehicle_facts_v2 where "
                + "source_url is null or observed_at is null", "vehicle fact lacks source evidence");
        requireZero(connection, "select count(*) from ford_read.comparison_facts_v2 where "
                + "source_url is null or observed_at is null", "comparison fact lacks source evidence");
        requireZero(connection, "select count(*) from ford_read.offers_v2 where "
                + "offer_type is null or offer_type not in ('promotion','discount','cashback','trade_in') "
                + "or source_url is null or observed_at is null", "promotion lacks allowed type or source evidence");
        requireZero(connection, "select count(*) from ford_read.catalog_documents_v1 d where d.offer_id is not null "
                + "and not exists (select 1 from ford_read.offers_v2 o where o.promotion_id=d.offer_id)",
                "promotion vector has no imported promotion");
    }

    private static void createCatalogSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            try (var existing = statement.executeQuery("select to_regnamespace('ford_read') is not null")) {
                if (existing.next() && existing.getBoolean(1)) {
                    throw invalid("catalog schema already exists; refusing to replace it");
                }
            }
            statement.execute("create extension if not exists vector");
            statement.execute("create extension if not exists pg_trgm");
            statement.execute("create schema ford_read");
            statement.execute("""
                    create table ford_read.vehicle_images_v1 (
                      model text, year integer, trim text, image_url text, alt_text text, image_type text,
                      width integer, height integer, source_url text, observed_at timestamptz)
                    """);
            statement.execute("""
                    create table ford_read.vehicle_facts_v1 (
                      model text, year integer, trim text, fact_type text, source_url text, observed_at timestamptz)
                    """);
            statement.execute("""
                    create table ford_read.vehicle_search_v1 (
                      model_year_id uuid, model text, normalized_name text, year integer, price numeric,
                      currency text, category text, body_type text, fuel_type text, model_page_url text,
                      price_source_url text, observed_at timestamptz)
                    """);
            statement.execute("""
                    create table ford_read.vehicle_variants_v2 (
                      variant_id uuid, model_year_id uuid, model text, normalized_model text, year integer,
                      trim text, normalized_trim text, price numeric, currency text, model_page_url text,
                      price_source_url text, observed_at timestamptz, comparison_url text)
                    """);
            statement.execute("""
                    create table ford_read.vehicle_facts_v2 (
                      variant_id uuid, model_year_id uuid, model text, year integer, trim text, category text,
                      fact_type text, fact_name text, fact_name_normalized text, fact_value text,
                      value_numeric numeric, fact_unit text, source_url text, observed_at timestamptz,
                      model_page_url text)
                    """);
            statement.execute("""
                    create table ford_read.comparison_facts_v2 (
                      model text, year integer, trim text, competitor_make text, competitor_model text,
                      competitor_year integer, competitor_variant text, category text, fact_name text,
                      fact_value text, fact_unit text, source_url text, observed_at timestamptz,
                      comparison_url text)
                    """);
            statement.execute("""
                    create table ford_read.offers_v2 (
                      promotion_id uuid, model text, year integer, title text, description text,
                      discount_amount numeric, discount_percent numeric, cash_price numeric, currency text,
                      valid_from date, valid_until date, conditions text, offer_type text, provider text,
                      down_payment_amount numeric, down_payment_percent numeric, monthly_payment numeric,
                      term_months integer, months_without_interest integer, annual_interest_rate numeric,
                      cat_percent numeric, requires_credit_approval boolean, source_url text,
                      observed_at timestamptz, model_page_url text, benefits jsonb, term_conflict jsonb,
                      tax_rate_percent numeric, opening_commission_rate_percent numeric,
                      maximum_down_payment_percent numeric, life_insurance_price numeric)
                    """);
            statement.execute("""
                    create view ford_read.promotions_v3 as
                    select * from ford_read.offers_v2
                    where offer_type in ('promotion','discount','cashback','trade_in')
                    """);
            statement.execute("""
                    create table ford_read.catalog_documents_v1 (
                      document_id uuid, document_type text, document_key text, title text,
                      semantic_content text, model_id uuid, model_year_id uuid, variant_id uuid, offer_id uuid,
                      source_url text, observed_at timestamptz, embedding_model text, embedding_status text,
                      embedding vector(1024),
                      search_vector tsvector generated always as
                        (to_tsvector('spanish', coalesce(title, '') || ' ' || coalesce(semantic_content, ''))) stored)
                    """);
            statement.execute("""
                    create table ford_read.page_links_v1 (
                      source_page_url text, source_page_type text, href text, target_url text,
                      anchor_text text, rel text, is_internal boolean, link_type text, observed_at timestamptz)
                    """);
            statement.execute("""
                    create view ford_read.catalog_pages_v1 as
                    with page_sources as (
                      select model_page_url as source_url,
                             concat_ws(' ', model, year::text) as title
                      from ford_read.vehicle_search_v1
                      union all
                      select model_page_url as source_url,
                             concat_ws(' ', model, year::text, trim) as title
                      from ford_read.vehicle_variants_v2
                      union all
                      select model_page_url as source_url,
                             concat_ws(' ', model, year::text, title) as title
                      from ford_read.offers_v2
                      union all
                      select source_url,
                             concat_ws(' ', model, year::text, title) as title
                      from ford_read.offers_v2
                    )
                    select source_url, max(title) as title
                    from page_sources
                    where source_url is not null and btrim(source_url) <> ''
                    group by source_url
                    """);
            statement.execute("""
                    create view ford_read.document_provenance_v1 as
                    select d.document_id, null::text as content_hash, null::text as page_content_hash,
                           d.title, d.document_type, d.source_url, v.model_page_url,
                           d.observed_at, v.model, v.year, v.trim
                    from ford_read.catalog_documents_v1 d
                    left join ford_read.vehicle_variants_v2 v on v.variant_id = d.variant_id
                    """);
            statement.execute("""
                    create view ford_read.catalog_status_v1 as
                    with observations as (
                      select observed_at from ford_read.vehicle_search_v1
                      union all select observed_at from ford_read.vehicle_variants_v2
                      union all select observed_at from ford_read.vehicle_facts_v2
                      union all select observed_at from ford_read.comparison_facts_v2
                      union all select observed_at from ford_read.offers_v2
                      union all select observed_at from ford_read.catalog_documents_v1
                    )
                    select
                      (select count(distinct normalized_name) from ford_read.vehicle_search_v1) as model_count,
                      (select count(distinct (normalized_name, year)) from ford_read.vehicle_search_v1)
                        as active_model_year_count,
                      (select count(*) from ford_read.vehicle_variants_v2) as variant_count,
                      (select count(*) from ford_read.catalog_pages_v1) as page_count,
                      (select count(*) from ford_read.catalog_documents_v1) as document_count,
                      (select count(*) from ford_read.catalog_documents_v1 where embedding_status='ready')
                        as embedded_document_count,
                      (select count(*) from ford_read.catalog_documents_v1 where embedding_status<>'ready')
                        as pending_embedding_count,
                      (select max(observed_at) from observations) as latest_page_crawl_at,
                      'LOCAL_SNAPSHOT'::text as latest_sync_status,
                      null::timestamptz as latest_sync_started_at,
                      null::timestamptz as latest_sync_finished_at
                    """);
        }
    }

    private static void grantReaderAccess(Connection connection, String readerRole) throws SQLException {
        var quotedRole = quoteIdentifier(readerRole);
        var database = connection.getCatalog();
        try (var statement = connection.createStatement()) {
            statement.execute("REVOKE CONNECT, TEMPORARY ON DATABASE " + quoteIdentifier(database) + " FROM PUBLIC");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database) + " TO " + quotedRole);
            statement.execute("GRANT USAGE ON SCHEMA ford_read TO " + quotedRole);
            for (var relation : READER_RELATIONS) {
                statement.execute("GRANT SELECT ON TABLE ford_read." + quoteIdentifier(relation)
                        + " TO " + quotedRole);
            }
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void requireZero(Connection connection, String sql, String failureMessage) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next() || result.getLong(1) != 0) throw invalid(failureMessage);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original safe validation error.
        }
    }

    private static void verifyHeader(Path file, String expectedHeader, String name) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (!expectedHeader.equals(reader.readLine())) throw invalid("CSV header mismatch: " + name);
        }
    }

    private static String text(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid("manifest field is missing: " + name);
        return value.asText();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IllegalArgumentException invalid(String safeMessage) {
        return new IllegalArgumentException(safeMessage);
    }

    private static Map<String, CsvTable> tables() {
        var tables = new LinkedHashMap<String, CsvTable>();
        tables.put("vehicle-search.csv", new CsvTable("ford_read.vehicle_search_v1",
                "model_year_id,model,normalized_name,year,price,currency,category,body_type,fuel_type,model_page_url,price_source_url,observed_at"));
        tables.put("vehicle-variants.csv", new CsvTable("ford_read.vehicle_variants_v2",
                "variant_id,model_year_id,model,normalized_model,year,trim,normalized_trim,price,currency,model_page_url,price_source_url,observed_at,comparison_url"));
        tables.put("vehicle-facts.csv", new CsvTable("ford_read.vehicle_facts_v2",
                "variant_id,model_year_id,model,year,trim,category,fact_type,fact_name,fact_name_normalized,fact_value,value_numeric,fact_unit,source_url,observed_at,model_page_url"));
        tables.put("comparison-facts.csv", new CsvTable("ford_read.comparison_facts_v2",
                "model,year,trim,competitor_make,competitor_model,competitor_year,competitor_variant,category,fact_name,fact_value,fact_unit,source_url,observed_at,comparison_url"));
        tables.put("promotions.csv", new CsvTable("ford_read.offers_v2",
                "promotion_id,model,year,title,description,discount_amount,discount_percent,cash_price,currency,valid_from,valid_until,conditions,offer_type,provider,down_payment_amount,down_payment_percent,monthly_payment,term_months,months_without_interest,annual_interest_rate,cat_percent,requires_credit_approval,source_url,observed_at,model_page_url,benefits,term_conflict,tax_rate_percent,opening_commission_rate_percent,maximum_down_payment_percent,life_insurance_price"));
        tables.put("product-vectors.csv", new CsvTable("ford_read.catalog_documents_v1",
                "document_id,document_type,document_key,title,semantic_content,model_id,model_year_id,variant_id,offer_id,source_url,observed_at,embedding_model,embedding_status,embedding"));
        return Map.copyOf(tables);
    }

    private record CsvTable(String tableName, String header) {
        String copySql() {
            var columns = header.replace(" ", "");
            return "COPY " + tableName + " (" + columns + ") FROM STDIN WITH (FORMAT csv, HEADER true)";
        }
    }

    private record Manifest(Instant refreshedAt, Map<String, Integer> rowCounts, String sha256) { }

    record SnapshotSummary(Instant refreshedAt, Map<String, Integer> rowCounts, String sha256) { }
}
