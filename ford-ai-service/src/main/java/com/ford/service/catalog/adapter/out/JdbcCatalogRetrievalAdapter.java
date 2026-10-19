package com.ford.service.catalog.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.catalog.api.CatalogCandidateLists;
import com.ford.service.catalog.api.CatalogQueryVector;
import com.ford.service.catalog.api.CatalogRetrievalCandidate;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleSearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Retrieves bounded candidate lists from the versioned document view, then maps them to structured evidence. */
final class JdbcCatalogRetrievalAdapter {

    private static final Logger log = LoggerFactory.getLogger(JdbcCatalogRetrievalAdapter.class);
    private static final int MAX_CANDIDATES = 40;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    JdbcCatalogRetrievalAdapter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    CatalogCandidateLists<VehicleEvidence> searchCandidates(VehicleSearchQuery query, CatalogQueryVector vector) {
        var text = blankToNull(query.query());
        if (text == null) {
            return new CatalogCandidateLists<>(List.of(), List.of(), List.of(), "not_requested");
        }

        var fullText = vehicleTextCandidates(query, text);
        var trigram = vehicleTrigramCandidates(query, text);
        var vectorCandidates = List.<CatalogRetrievalCandidate<VehicleEvidence>>of();
        var semanticSearch = "unavailable";
        if (vector != null) {
            try {
                if (hasCompatibleVehicleVectors(query, vector.storedModel())) {
                    vectorCandidates = vehicleVectorCandidates(query, vector);
                    semanticSearch = "ready";
                }
            } catch (DataAccessException failure) {
                log.warn("catalog vector retrieval unavailable: failureCategory=database exceptionType={}",
                        failure.getClass().getSimpleName());
            }
        }
        return new CatalogCandidateLists<>(fullText, trigram, vectorCandidates, semanticSearch);
    }

    CatalogCandidateLists<OfferEvidence> offerCandidates(OfferQuery query, CatalogQueryVector vector) {
        var text = blankToNull(query.query());
        if (text == null) {
            return new CatalogCandidateLists<>(List.of(), List.of(), List.of(), "not_requested");
        }

        var fullText = offerTextCandidates(query, text);
        var trigram = offerTrigramCandidates(query, text);
        var vectorCandidates = List.<CatalogRetrievalCandidate<OfferEvidence>>of();
        var semanticSearch = "unavailable";
        if (vector != null) {
            try {
                if (hasCompatibleOfferVectors(query, vector.storedModel())) {
                    vectorCandidates = offers(query, vector, text);
                    semanticSearch = "ready";
                }
            } catch (DataAccessException failure) {
                log.warn("catalog offer vector retrieval unavailable: failureCategory=database exceptionType={}",
                        failure.getClass().getSimpleName());
            }
        }
        return new CatalogCandidateLists<>(fullText, trigram, vectorCandidates, semanticSearch);
    }

    private boolean hasCompatibleVehicleVectors(VehicleSearchQuery query, String model) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                          SELECT 1
                          FROM ford_read.catalog_documents_v1 d
                          JOIN ford_read.vehicle_search_v1 base ON base.model_year_id = d.model_year_id
                          WHERE d.offer_id IS NULL
                            AND d.source_url IS NOT NULL AND d.observed_at IS NOT NULL
                            AND d.embedding_status = 'ready' AND d.embedding_model = :storedModel
                            AND d.embedding IS NOT NULL
                            AND (cast(:model as varchar) IS NULL OR lower(base.model) = lower(cast(:model as varchar)))
                            AND (cast(:modelYear as integer) IS NULL OR base.year = cast(:modelYear as integer))
                            AND (cast(:minPrice as decimal) IS NULL OR
                                 (base.price_source_url IS NOT NULL AND base.observed_at IS NOT NULL
                                  AND base.price >= cast(:minPrice as decimal)))
                            AND (cast(:maxPrice as decimal) IS NULL OR
                                 (base.price_source_url IS NOT NULL AND base.observed_at IS NOT NULL
                                  AND base.price <= cast(:maxPrice as decimal)))
                            AND (cast(:category as varchar) IS NULL OR lower(base.category) LIKE '%' || lower(cast(:category as varchar)) || '%')
                            AND (cast(:bodyType as varchar) IS NULL OR lower(base.body_type) LIKE '%' || lower(cast(:bodyType as varchar)) || '%')
                            AND (cast(:fuelType as varchar) IS NULL OR lower(base.fuel_type) LIKE '%' || lower(cast(:fuelType as varchar)) || '%')
                        )
                        """)
                .param("storedModel", model)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("minPrice", query.minPrice())
                .param("maxPrice", query.maxPrice())
                .param("category", blankToNull(query.category()))
                .param("bodyType", blankToNull(query.bodyType()))
                .param("fuelType", blankToNull(query.fuelType()))
                .query(Boolean.class)
                .single());
    }

    private boolean hasCompatibleOfferVectors(OfferQuery query, String model) {
        var asOf = query.asOf() == null ? LocalDate.now(java.time.ZoneOffset.UTC) : query.asOf();
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                          SELECT 1
                          FROM ford_read.catalog_documents_v1 d
                          JOIN ford_read.offers_v2 offer ON offer.promotion_id = d.offer_id
                          WHERE d.offer_id IS NOT NULL
                            AND d.source_url IS NOT NULL AND d.observed_at IS NOT NULL
                            AND offer.source_url IS NOT NULL AND offer.observed_at IS NOT NULL
                            AND d.embedding_status = 'ready' AND d.embedding_model = :storedModel
                            AND d.embedding IS NOT NULL
                            AND (cast(:model as varchar) IS NULL OR lower(offer.model) = lower(cast(:model as varchar)))
                            AND (cast(:modelYear as integer) IS NULL OR offer.year = cast(:modelYear as integer))
                            AND (cast(:offerType as varchar) IS NULL OR offer.offer_type = cast(:offerType as varchar))
                            AND (cast(:provider as varchar) IS NULL OR lower(offer.provider) = lower(cast(:provider as varchar)))
                            AND (cast(:maxCashPrice as decimal) IS NULL OR offer.cash_price <= cast(:maxCashPrice as decimal))
                            AND (cast(:maxMonthlyPayment as decimal) IS NULL OR offer.monthly_payment <= cast(:maxMonthlyPayment as decimal))
                            AND (cast(:maxDownPayment as decimal) IS NULL OR offer.down_payment_amount <= cast(:maxDownPayment as decimal))
                            AND (cast(:minimumMonths as integer) IS NULL OR offer.months_without_interest >= cast(:minimumMonths as integer))
                            AND (cast(:termMonths as integer) IS NULL OR offer.term_months = cast(:termMonths as integer))
                            AND (NOT :activeOnly OR (offer.valid_from IS NULL OR offer.valid_from <= :asOf)
                                 AND (offer.valid_until IS NULL OR offer.valid_until >= :asOf))
                        )
                        """)
                .param("storedModel", model)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("offerType", blankToNull(query.offerType()))
                .param("provider", blankToNull(query.provider()))
                .param("maxCashPrice", query.maxCashPrice())
                .param("maxMonthlyPayment", query.maxMonthlyPayment())
                .param("maxDownPayment", query.maxDownPayment())
                .param("minimumMonths", query.minimumMonthsWithoutInterest())
                .param("termMonths", query.termMonths())
                .param("activeOnly", query.activeOnly() == null || query.activeOnly())
                .param("asOf", asOf)
                .query(Boolean.class)
                .single());
    }

    private List<CatalogRetrievalCandidate<VehicleEvidence>> vehicleTextCandidates(
            VehicleSearchQuery query, String text) {
        var rows = vehicleQuery(query,
                "d.search_vector @@ websearch_to_tsquery('spanish', :query)",
                "ts_rank_cd(d.search_vector, websearch_to_tsquery('spanish', :query))",
                "ts_rank_cd(d.search_vector, websearch_to_tsquery('spanish', :query)) DESC",
                text).query(this::vehicleCandidate).list();
        return distinct(rows, candidateLimit(query.limit()));
    }

    private List<CatalogRetrievalCandidate<VehicleEvidence>> vehicleTrigramCandidates(
            VehicleSearchQuery query, String text) {
        var score = "word_similarity(base.normalized_name, lower(:query))";
        var rows = vehicleQuery(query, "base.normalized_name <% lower(:query)",
                score, score + " DESC", text)
                .query(this::vehicleCandidate).list();
        return distinct(rows, candidateLimit(query.limit()));
    }

    private List<CatalogRetrievalCandidate<VehicleEvidence>> vehicleVectorCandidates(
            VehicleSearchQuery query, CatalogQueryVector vector) {
        var distance = "d.embedding <=> cast(:queryVector as vector)";
        var statement = vehicleQuery(query,
                "d.embedding_status = 'ready' AND d.embedding_model = :storedModel AND d.embedding IS NOT NULL",
                "1 - (" + distance + ")", distance + " ASC", query.query())
                .param("storedModel", vector.storedModel())
                .param("queryVector", vectorLiteral(vector));
        return distinct(statement.query(this::vehicleCandidate).list(), candidateLimit(query.limit()));
    }

    private JdbcClient.StatementSpec vehicleQuery(VehicleSearchQuery query, String retrievalFilter,
                                                  String score, String rankOrder, String text) {
        var sql = """
                SELECT d.document_key AS stable_id,
                       concat_ws(' ', coalesce(d.title, ''), coalesce(d.semantic_content, '')) AS search_text,
                       base.model, base.year, null::text AS trim,
                       CASE WHEN base.price_source_url IS NOT NULL AND base.observed_at IS NOT NULL
                            THEN base.price END AS price,
                       base.currency, base.model_page_url, base.price_source_url,
                       coalesce(base.observed_at, d.observed_at) AS observed_at,
                       %s AS retrieval_score
                FROM ford_read.catalog_documents_v1 d
                JOIN ford_read.vehicle_search_v1 base ON base.model_year_id = d.model_year_id
                WHERE d.offer_id IS NULL
                  AND d.source_url IS NOT NULL AND d.observed_at IS NOT NULL
                  AND (cast(:model as varchar) IS NULL OR lower(base.model) = lower(cast(:model as varchar)))
                  AND (cast(:modelYear as integer) IS NULL OR base.year = cast(:modelYear as integer))
                  AND (cast(:minPrice as decimal) IS NULL OR
                       (base.price_source_url IS NOT NULL AND base.observed_at IS NOT NULL
                        AND base.price >= cast(:minPrice as decimal)))
                  AND (cast(:maxPrice as decimal) IS NULL OR
                       (base.price_source_url IS NOT NULL AND base.observed_at IS NOT NULL
                        AND base.price <= cast(:maxPrice as decimal)))
                  AND (cast(:category as varchar) IS NULL OR lower(base.category) LIKE '%%' || lower(cast(:category as varchar)) || '%%')
                  AND (cast(:bodyType as varchar) IS NULL OR lower(base.body_type) LIKE '%%' || lower(cast(:bodyType as varchar)) || '%%')
                  AND (cast(:fuelType as varchar) IS NULL OR lower(base.fuel_type) LIKE '%%' || lower(cast(:fuelType as varchar)) || '%%')
                  AND %s
                ORDER BY %s, d.document_id
                LIMIT :candidateLimit
                """.formatted(score, retrievalFilter, rankOrder);
        return jdbc.sql(sql)
                .param("query", text)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("minPrice", query.minPrice())
                .param("maxPrice", query.maxPrice())
                .param("category", blankToNull(query.category()))
                .param("bodyType", blankToNull(query.bodyType()))
                .param("fuelType", blankToNull(query.fuelType()))
                .param("candidateLimit", queryLimit(query.limit()));
    }

    private List<CatalogRetrievalCandidate<OfferEvidence>> offerTextCandidates(OfferQuery query, String text) {
        var rows = offerQuery(query,
                "d.search_vector @@ websearch_to_tsquery('spanish', :query)",
                "ts_rank_cd(d.search_vector, websearch_to_tsquery('spanish', :query))",
                "ts_rank_cd(d.search_vector, websearch_to_tsquery('spanish', :query)) DESC",
                text, null).query(this::offerCandidate).list();
        return distinct(rows, candidateLimit(query.limit()));
    }

    private List<CatalogRetrievalCandidate<OfferEvidence>> offerTrigramCandidates(OfferQuery query, String text) {
        var score = "word_similarity(lower(concat_ws(' ', offer.model, offer.title, offer.description)), lower(:query))";
        var rows = offerQuery(query, score + " >= 0.15", score, score + " DESC", text, null)
                .query(this::offerCandidate).list();
        return distinct(rows, candidateLimit(query.limit()));
    }

    private List<CatalogRetrievalCandidate<OfferEvidence>> offers(
            OfferQuery query, CatalogQueryVector vector, String text) {
        var distance = "d.embedding <=> cast(:queryVector as vector)";
        var statement = offerQuery(query,
                "d.embedding_status = 'ready' AND d.embedding_model = :storedModel AND d.embedding IS NOT NULL",
                "1 - (" + distance + ")", distance + " ASC", text, vector)
                .param("storedModel", vector.storedModel())
                .param("queryVector", vectorLiteral(vector));
        return distinct(statement.query(this::offerCandidate).list(), candidateLimit(query.limit()));
    }

    private JdbcClient.StatementSpec offerQuery(OfferQuery query, String retrievalFilter, String score,
                                                 String rankOrder, String text, CatalogQueryVector vector) {
        var sql = """
                SELECT d.document_key AS stable_id,
                       concat_ws(' ', coalesce(d.title, ''), coalesce(d.semantic_content, '')) AS search_text,
                       offer.promotion_id::text AS offer_id,
                       offer.model, offer.year, offer.title, offer.offer_type, offer.provider,
                       offer.cash_price, offer.monthly_payment, offer.down_payment_amount,
                       offer.down_payment_percent, offer.term_months, offer.months_without_interest,
                       offer.annual_interest_rate, offer.cat_percent, offer.currency,
                       offer.valid_from, offer.valid_until,
                       CASE WHEN offer.valid_from IS NULL OR offer.valid_until IS NULL THEN 'unknown'
                            WHEN offer.valid_from > :asOf THEN 'upcoming'
                            WHEN offer.valid_until < :asOf THEN 'expired'
                            ELSE 'active' END AS validity_status,
                       offer.requires_credit_approval, offer.conditions, offer.source_url,
                       offer.model_page_url, offer.observed_at,
                       cast(offer.benefits as varchar) AS benefits_json,
                       cast(offer.term_conflict as varchar) AS term_conflict_json,
                       offer.tax_rate_percent, offer.opening_commission_rate_percent,
                       offer.maximum_down_payment_percent, offer.life_insurance_price,
                       %s AS retrieval_score
                FROM ford_read.catalog_documents_v1 d
                JOIN ford_read.offers_v2 offer ON offer.promotion_id = d.offer_id
                WHERE d.offer_id IS NOT NULL
                  AND d.source_url IS NOT NULL AND d.observed_at IS NOT NULL
                  AND offer.source_url IS NOT NULL AND offer.observed_at IS NOT NULL
                  AND (cast(:model as varchar) IS NULL OR lower(offer.model) = lower(cast(:model as varchar)))
                  AND (cast(:modelYear as integer) IS NULL OR offer.year = cast(:modelYear as integer))
                  AND (cast(:offerType as varchar) IS NULL OR offer.offer_type = cast(:offerType as varchar))
                  AND (cast(:provider as varchar) IS NULL OR lower(offer.provider) = lower(cast(:provider as varchar)))
                  AND (cast(:maxCashPrice as decimal) IS NULL OR offer.cash_price <= cast(:maxCashPrice as decimal))
                  AND (cast(:maxMonthlyPayment as decimal) IS NULL OR offer.monthly_payment <= cast(:maxMonthlyPayment as decimal))
                  AND (cast(:maxDownPayment as decimal) IS NULL OR offer.down_payment_amount <= cast(:maxDownPayment as decimal))
                  AND (cast(:minimumMonths as integer) IS NULL OR offer.months_without_interest >= cast(:minimumMonths as integer))
                  AND (cast(:termMonths as integer) IS NULL OR offer.term_months = cast(:termMonths as integer))
                  AND (NOT :activeOnly OR (offer.valid_from IS NULL OR offer.valid_from <= :asOf)
                       AND (offer.valid_until IS NULL OR offer.valid_until >= :asOf))
                  AND %s
                ORDER BY %s, offer.promotion_id
                LIMIT :candidateLimit
                """.formatted(score, retrievalFilter, rankOrder);
        var asOf = query.asOf() == null ? LocalDate.now(java.time.ZoneOffset.UTC) : query.asOf();
        return jdbc.sql(sql)
                .param("query", text)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("offerType", blankToNull(query.offerType()))
                .param("provider", blankToNull(query.provider()))
                .param("maxCashPrice", query.maxCashPrice())
                .param("maxMonthlyPayment", query.maxMonthlyPayment())
                .param("maxDownPayment", query.maxDownPayment())
                .param("minimumMonths", query.minimumMonthsWithoutInterest())
                .param("termMonths", query.termMonths())
                .param("activeOnly", query.activeOnly() == null || query.activeOnly())
                .param("asOf", asOf)
                .param("candidateLimit", queryLimit(query.limit()));
    }

    private CatalogRetrievalCandidate<VehicleEvidence> vehicleCandidate(ResultSet rs, int rowNumber)
            throws SQLException {
        var evidence = new VehicleEvidence(rs.getString("model"), rs.getInt("year"), rs.getString("trim"),
                rs.getBigDecimal("price"), rs.getString("currency"), rs.getString("model_page_url"),
                rs.getString("price_source_url"), instant(rs, "observed_at"), List.of());
        return new CatalogRetrievalCandidate<>(rs.getString("stable_id"), rs.getString("search_text"), evidence);
    }

    private CatalogRetrievalCandidate<OfferEvidence> offerCandidate(ResultSet rs, int rowNumber) throws SQLException {
        var evidence = new OfferEvidence(rs.getString("offer_id"), rs.getString("model"), nullableInt(rs, "year"),
                rs.getString("title"), rs.getString("offer_type"), rs.getString("provider"),
                rs.getBigDecimal("cash_price"), rs.getBigDecimal("monthly_payment"),
                rs.getBigDecimal("down_payment_amount"), rs.getBigDecimal("down_payment_percent"),
                nullableInt(rs, "term_months"), nullableInt(rs, "months_without_interest"),
                rs.getBigDecimal("annual_interest_rate"), rs.getBigDecimal("cat_percent"),
                rs.getString("currency"), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_until", LocalDate.class), rs.getString("validity_status"),
                rs.getBoolean("requires_credit_approval"), rs.getString("conditions"),
                rs.getString("source_url"), rs.getString("model_page_url"), instant(rs, "observed_at"),
                json(rs, "benefits_json"), json(rs, "term_conflict_json"),
                rs.getBigDecimal("tax_rate_percent"), rs.getBigDecimal("opening_commission_rate_percent"),
                rs.getBigDecimal("maximum_down_payment_percent"), rs.getBigDecimal("life_insurance_price"));
        return new CatalogRetrievalCandidate<>(rs.getString("stable_id"), rs.getString("search_text"), evidence);
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column);
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Catalog candidate JSON is invalid", exception);
        }
    }

    private static int candidateLimit(int resultLimit) {
        return Math.min(MAX_CANDIDATES, Math.max(10, resultLimit * 4));
    }

    private static int queryLimit(int resultLimit) {
        return Math.min(MAX_CANDIDATES * 4, candidateLimit(resultLimit) * 4);
    }

    private static <T> List<CatalogRetrievalCandidate<T>> distinct(
            List<CatalogRetrievalCandidate<T>> candidates, int limit) {
        var unique = new LinkedHashMap<String, CatalogRetrievalCandidate<T>>();
        for (var candidate : candidates) {
            unique.putIfAbsent(candidate.stableId(), candidate);
            if (unique.size() == limit) break;
        }
        return List.copyOf(unique.values());
    }

    private static String vectorLiteral(CatalogQueryVector vector) {
        return "[" + vector.values().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
