package com.ford.service.ingestion.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ingestion.application.port.out.CanonicalDataWriter;
import com.ford.service.shared.SourceCandidate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcCanonicalDataWriter implements CanonicalDataWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCanonicalDataWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    @Transactional
    public int write(UUID tenantId, UUID syncRunId, List<SourceCandidate> candidates) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(syncRunId);
        Objects.requireNonNull(candidates);
        var applied = 0;
        for (var candidate : candidates) {
            var node = parse(candidate.payload());
            if (isInventory(candidate, node)) {
                upsertInventory(tenantId, syncRunId, candidate, node);
            } else {
                upsertCatalog(tenantId, syncRunId, candidate, node);
            }
            applied++;
        }
        return applied;
    }

    private void upsertCatalog(UUID tenantId, UUID syncRunId, SourceCandidate candidate, JsonNode node) {
        var metadata = candidate.metadata();
        var modelCode = firstText(node, "modelCode", "model_code", "code")
                .orElse(metadata.sourceRecordId());
        var modelName = firstText(node, "modelName", "model_name", "model", "name")
                .orElse(modelCode);
        var modelYear = node.has("modelYear") ? node.get("modelYear").asInt()
                : node.has("model_year") ? node.get("model_year").asInt()
                : metadata.observedAt().atZone(java.time.ZoneOffset.UTC).getYear();
        var modelId = jdbc.sql("""
                insert into vehicle_model
                    (id, tenant_id, model_code, name, model_year, description,
                     source_system, source_record_id, observed_at, freshness_status, raw_payload, sync_run_id)
                values (:id, :tenantId, :modelCode, :name, :modelYear, :description,
                        :sourceSystem, :sourceRecordId, :observedAt, :freshnessStatus,
                        cast(:rawPayload as jsonb), :syncRunId)
                on conflict (tenant_id, model_code, model_year) do update set
                    name = excluded.name, description = excluded.description,
                    source_system = excluded.source_system, source_record_id = excluded.source_record_id,
                    observed_at = excluded.observed_at, freshness_status = excluded.freshness_status,
                    raw_payload = excluded.raw_payload, sync_run_id = excluded.sync_run_id
                returning id
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("modelCode", modelCode)
                .param("name", modelName)
                .param("modelYear", modelYear)
                .param("description", firstText(node, "description").orElse(null))
                .param("sourceSystem", metadata.sourceSystem())
                .param("sourceRecordId", metadata.sourceRecordId())
                .param("observedAt", Timestamp.from(metadata.observedAt()))
                .param("freshnessStatus", metadata.freshnessStatus().name())
                .param("rawPayload", candidate.payload())
                .param("syncRunId", syncRunId)
                .query(UUID.class)
                .single();

        var variantCode = firstText(node, "variantCode", "variant_code", "versionCode")
                .orElse(modelCode);
        var variantName = firstText(node, "variantName", "variant_name", "version")
                .orElse(firstText(node, "name").orElse(modelName));
        var specifications = node.has("specifications") ? node.get("specifications").toString() : "{}";
        var variantId = jdbc.sql("""
                insert into vehicle_variant
                    (id, tenant_id, model_id, variant_code, name, specifications)
                values (:id, :tenantId, :modelId, :variantCode, :name, cast(:specifications as jsonb))
                on conflict (tenant_id, variant_code) do update set
                    model_id = excluded.model_id, name = excluded.name, specifications = excluded.specifications
                returning id
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("modelId", modelId)
                .param("variantCode", variantCode)
                .param("name", variantName)
                .param("specifications", specifications)
                .query(UUID.class)
                .single();

        price(node).ifPresent(amount -> jdbc.sql("""
                insert into vehicle_price
                    (id, tenant_id, variant_id, currency, amount, effective_from, effective_to,
                     source_system, source_record_id, observed_at, freshness_status, raw_payload, sync_run_id)
                values (:id, :tenantId, :variantId, 'MXN', :amount, :effectiveFrom, :effectiveTo,
                        :sourceSystem, :sourceRecordId, :observedAt, :freshnessStatus,
                        cast(:rawPayload as jsonb), :syncRunId)
                on conflict (tenant_id, variant_id, source_system, source_record_id) do update set
                    amount = excluded.amount, effective_from = excluded.effective_from,
                    effective_to = excluded.effective_to, observed_at = excluded.observed_at,
                    freshness_status = excluded.freshness_status, raw_payload = excluded.raw_payload,
                    sync_run_id = excluded.sync_run_id
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("variantId", variantId)
                .param("amount", amount)
                .param("effectiveFrom", Timestamp.from(metadata.effectiveFrom() == null
                        ? metadata.observedAt() : metadata.effectiveFrom()))
                .param("effectiveTo", metadata.effectiveTo() == null ? null : Timestamp.from(metadata.effectiveTo()))
                .param("sourceSystem", metadata.sourceSystem())
                .param("sourceRecordId", metadata.sourceRecordId())
                .param("observedAt", Timestamp.from(metadata.observedAt()))
                .param("freshnessStatus", metadata.freshnessStatus().name())
                .param("rawPayload", candidate.payload())
                .param("syncRunId", syncRunId)
                .update());
    }

    private void upsertInventory(UUID tenantId, UUID syncRunId, SourceCandidate candidate, JsonNode node) {
        var metadata = candidate.metadata();
        var variantCode = firstText(node, "variantCode", "variant_code", "code")
                .orElseThrow(() -> new IllegalArgumentException("Inventory record requires variantCode"));
        var variantId = jdbc.sql("select id from vehicle_variant where tenant_id = :tenantId and variant_code = :variantCode")
                .param("tenantId", tenantId)
                .param("variantCode", variantCode)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Inventory variant is not canonical: " + variantCode));
        jdbc.sql("""
                insert into vehicle_unit
                    (id, tenant_id, variant_id, external_stock_id, dealership_code, status,
                     observed_at, freshness_status, raw_payload, sync_run_id)
                values (:id, :tenantId, :variantId, :externalStockId, :dealershipCode, :status,
                        :observedAt, :freshnessStatus, cast(:rawPayload as jsonb), :syncRunId)
                on conflict (tenant_id, external_stock_id) do update set
                    variant_id = excluded.variant_id, dealership_code = excluded.dealership_code,
                    status = excluded.status, observed_at = excluded.observed_at,
                    freshness_status = excluded.freshness_status, raw_payload = excluded.raw_payload,
                    sync_run_id = excluded.sync_run_id
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("variantId", variantId)
                .param("externalStockId", firstText(node, "externalStockId", "external_stock_id", "stockId", "id")
                        .orElse(metadata.sourceRecordId()))
                .param("dealershipCode", firstText(node, "dealershipCode", "dealership_code", "dealerCode")
                        .orElse("unknown"))
                .param("status", firstText(node, "status").orElse("AVAILABLE"))
                .param("observedAt", Timestamp.from(metadata.observedAt()))
                .param("freshnessStatus", metadata.freshnessStatus().name())
                .param("rawPayload", candidate.payload())
                .param("syncRunId", syncRunId)
                .update();
    }

    private boolean isInventory(SourceCandidate candidate, JsonNode node) {
        return candidate.metadata().sourceSystem().toLowerCase(java.util.Locale.ROOT).contains("inventory")
                || node.has("stockId") || node.has("externalStockId") || node.has("dealershipCode");
    }

    private java.util.Optional<BigDecimal> price(JsonNode node) {
        var value = node.get("price");
        if (value == null) {
            value = node.get("amount");
        }
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        if (value.isObject()) {
            value = value.get("amount");
        }
        return value == null || value.isNull() ? java.util.Optional.empty()
                : java.util.Optional.of(value.decimalValue());
    }

    private java.util.Optional<String> firstText(JsonNode node, String... names) {
        for (var name : names) {
            var value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return java.util.Optional.of(value.asText().strip());
            }
        }
        return java.util.Optional.empty();
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Canonical source payload is not valid JSON", exception);
        }
    }
}
