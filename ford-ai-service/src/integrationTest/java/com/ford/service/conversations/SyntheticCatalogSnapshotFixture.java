package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a small, unmistakably synthetic catalog snapshot for disposable integration-test databases. */
final class SyntheticCatalogSnapshotFixture {

    private static final String OBSERVED_AT = "2026-10-16T10:00:00Z";
    private static final String RANGER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String MAVERICK_ID = "55555555-5555-4555-8555-555555555555";
    private static final String RANGER_VARIANT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String MAVERICK_VARIANT_ID = "66666666-6666-4666-8666-666666666666";
    private static final String RANGER_PAGE = "https://catalog.synthetic.invalid/ranger/";
    private static final String MAVERICK_PAGE = "https://catalog.synthetic.invalid/maverick/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, String> HEADERS = Map.of(
            "vehicle-search.csv", "model_year_id,model,normalized_name,year,price,currency,category,body_type,fuel_type,model_page_url,price_source_url,observed_at",
            "vehicle-variants.csv", "variant_id,model_year_id,model,normalized_model,year,trim,normalized_trim,price,currency,model_page_url,price_source_url,observed_at,comparison_url",
            "vehicle-facts.csv", "variant_id,model_year_id,model,year,trim,category,fact_type,fact_name,fact_name_normalized,fact_value,value_numeric,fact_unit,source_url,observed_at,model_page_url",
            "comparison-facts.csv", "model,year,trim,competitor_make,competitor_model,competitor_year,competitor_variant,category,fact_name,fact_value,fact_unit,source_url,observed_at,comparison_url",
            "promotions.csv", "promotion_id,model,year,title,description,discount_amount,discount_percent,cash_price,currency,valid_from,valid_until,conditions,offer_type,provider,down_payment_amount,down_payment_percent,monthly_payment,term_months,months_without_interest,annual_interest_rate,cat_percent,requires_credit_approval,source_url,observed_at,model_page_url,benefits,term_conflict,tax_rate_percent,opening_commission_rate_percent,maximum_down_payment_percent,life_insurance_price",
            "product-vectors.csv", "document_id,document_type,document_key,title,semantic_content,model_id,model_year_id,variant_id,offer_id,source_url,observed_at,embedding_model,embedding_status,embedding");

    private SyntheticCatalogSnapshotFixture() { }

    static Path write(Path directory) throws IOException {
        var snapshot = directory.toAbsolutePath().normalize();
        Files.createDirectories(snapshot);
        writeCsv(snapshot, "vehicle-search.csv", List.of(
                "Ranger,ranger,907000,Pickup,Pickup,Gasolina," + RANGER_PAGE + ",ranger/price/",
                "Maverick,maverick,775000,Pickup,Pickup,Híbrida," + MAVERICK_PAGE + ",maverick/price/"),
                (model, name, price, category, body, fuel, page, pricePath) -> String.join(",",
                        modelYearId(model), model, name, "2027", price, "MXN", category, body, fuel,
                        page, syntheticUrl(pricePath), OBSERVED_AT));
        writeCsv(snapshot, "vehicle-variants.csv", List.of(
                variant(RANGER_VARIANT_ID, RANGER_ID, "Ranger", "XLT Gasolina 4x2", "907000", RANGER_PAGE,
                        "ranger/price/", "ranger/compare/"),
                variant(MAVERICK_VARIANT_ID, MAVERICK_ID, "Maverick", "XLT Híbrida", "775000", MAVERICK_PAGE,
                        "maverick/price/", "maverick/compare/")));
        writeCsv(snapshot, "vehicle-facts.csv", List.of(
                String.join(",", RANGER_VARIANT_ID, RANGER_ID, "Ranger", "2027", "XLT Gasolina 4x2", "Motor",
                        "specification", "Motor", "motor", "2.0L", "", "L",
                        syntheticUrl("ranger/specs/"), OBSERVED_AT, RANGER_PAGE),
                String.join(",", MAVERICK_VARIANT_ID, MAVERICK_ID, "Maverick", "2027", "XLT Híbrida", "Motor",
                        "specification", "Motor", "motor", "2.5L Híbrido", "", "L",
                        syntheticUrl("maverick/specs/"), OBSERVED_AT, MAVERICK_PAGE)));
        writeCsv(snapshot, "comparison-facts.csv", List.of(
                String.join(",", "Ranger", "2027", "XLT Gasolina 4x2", "Ford", "Maverick", "2027", "XLT Híbrida",
                        "Powertrain", "Motor", "2.0L", "L", syntheticUrl("ranger/compare/"), OBSERVED_AT,
                        syntheticUrl("ranger/compare/"))));
        writeCsv(snapshot, "promotions.csv", List.of(
                String.join(",", "33333333-3333-4333-8333-333333333333", "Ranger", "2027",
                        "Synthetic promotion", "Synthetic offer", "15000", "", "907000", "MXN", "2026-10-01",
                        "2026-12-31", "Synthetic conditions", "promotion", "Synthetic provider", "250000", "",
                        "12000", "60", "0", "0.1125", "", "true", syntheticUrl("ranger/offer/"), OBSERVED_AT,
                        RANGER_PAGE, "[]", "[]", "", "", "", "")));
        writeCsv(snapshot, "product-vectors.csv", List.of(
                vectorRow("44444444-4444-4444-8444-444444444444", "ranger-profile", "Ranger profile",
                        "Ford Ranger pickup para trabajo y familia; XLT Gasolina 4x2, motor 2.0L.", RANGER_ID,
                        RANGER_VARIANT_ID, RANGER_PAGE),
                vectorRow("77777777-7777-4777-8777-777777777777", "maverick-profile", "Maverick profile",
                        "Ford Maverick pickup híbrida para ciudad, estacionamiento y uso familiar; XLT Híbrida.",
                        MAVERICK_ID, MAVERICK_VARIANT_ID, MAVERICK_PAGE)));
        writeManifest(snapshot);
        return snapshot;
    }

    private static String variant(String variantId, String modelYearId, String model, String trim, String price,
                                  String page, String pricePath, String comparisonPath) {
        return String.join(",", variantId, modelYearId, model, model.toLowerCase(java.util.Locale.ROOT), "2027", trim,
                normalize(trim), price, "MXN", page, syntheticUrl(pricePath), OBSERVED_AT,
                syntheticUrl(comparisonPath));
    }

    private static String vectorRow(String documentId, String key, String title, String content, String modelYearId,
                                    String variantId, String page) {
        return csvRow(documentId, "vehicle_profile", key, title, content, "", modelYearId, variantId, "", page,
                OBSERVED_AT, "bge-m3", "ready", vector(1024));
    }

    private static void writeCsv(Path directory, String name, List<String> rows) throws IOException {
        writeContent(directory, name, HEADERS.get(name), rows);
    }

    private static void writeCsv(Path directory, String name, List<String> rowFields, RowFormatter formatter)
            throws IOException {
        var rows = new ArrayList<String>();
        for (var csv : rowFields) {
            var fields = csv.split(",", -1);
            rows.add(formatter.format(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                    fields[7]));
        }
        writeCsv(directory, name, rows);
    }

    private static void writeContent(Path directory, String name, String header, List<String> rows)
            throws IOException {
        var content = new StringBuilder(header).append('\n');
        rows.forEach(row -> content.append(row).append('\n'));
        Files.writeString(directory.resolve(name), content);
    }

    private static void writeManifest(Path directory) throws IOException {
        var files = new ArrayList<Map<String, Object>>();
        try {
            for (var name : LocalCatalogSnapshotLoader.FILE_NAMES) {
                var path = directory.resolve(name);
                var bytes = Files.readAllBytes(path);
                files.add(Map.of("name", name,
                        "rowCount", Math.toIntExact(Files.lines(path).count() - 1),
                        "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
            }
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("sourceLabel", "test-only synthetic CSV");
            manifest.put("refreshedAt", Instant.parse(OBSERVED_AT).toString());
            manifest.put("files", files);
            OBJECT_MAPPER.writeValue(directory.resolve("snapshot-manifest.json").toFile(), manifest);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String modelYearId(String model) {
        return "Ranger".equals(model) ? RANGER_ID : MAVERICK_ID;
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String syntheticUrl(String path) {
        return "https://catalog.synthetic.invalid/" + path;
    }

    private static String vector(int dimension) {
        return "[" + String.join(",", java.util.Collections.nCopies(dimension, "0.0")) + "]";
    }

    private static String csvRow(String... values) {
        return java.util.Arrays.stream(values).map(value -> value.isEmpty() ? ""
                        : "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    @FunctionalInterface
    private interface RowFormatter {
        String format(String model, String name, String price, String category, String body,
                      String fuel, String page, String pricePath);
    }
}
