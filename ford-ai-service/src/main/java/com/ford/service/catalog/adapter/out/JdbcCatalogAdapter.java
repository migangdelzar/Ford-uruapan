package com.ford.service.catalog.adapter.out;

import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.CatalogPageLinkEvidence;
import com.ford.service.catalog.api.DocumentProvenance;
import com.ford.service.catalog.api.CatalogStatus;
import com.ford.service.catalog.api.CatalogCandidateLists;
import com.ford.service.catalog.api.CatalogQueryVector;
import com.ford.service.catalog.api.VariantComparisonEvidence;
import com.ford.service.catalog.api.CatalogPageLinkPage;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleFact;
import com.ford.service.catalog.api.VehicleImage;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleSearchQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Array;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.text.Normalizer;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Read-only adapter over Ford-scraping's versioned public catalog views. */
public final class JdbcCatalogAdapter implements CatalogPort {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcCatalogRetrievalAdapter retrieval;

    public JdbcCatalogAdapter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.retrieval = new JdbcCatalogRetrievalAdapter(jdbc, objectMapper);
    }

    @Override
    public List<VehicleEvidence> search(VehicleSearchQuery query) {
        var sql = """
                select model, year,
                       case when price_source_url is not null and observed_at is not null then price end as price,
                       currency, category, body_type, fuel_type,
                       model_page_url, price_source_url, observed_at
                from ford_read.vehicle_search_v1
                where (cast(:query as varchar) is null or lower(model || ' ' || coalesce(category, '')
                          || ' ' || coalesce(body_type, '') || ' ' || coalesce(fuel_type, ''))
                          like '%' || lower(cast(:query as varchar)) || '%')
                  and (cast(:model as varchar) is null or lower(model) like '%' || lower(cast(:model as varchar)) || '%')
                  and (cast(:modelYear as integer) is null or year = cast(:modelYear as integer))
                  and (cast(:minPrice as decimal) is null or price >= cast(:minPrice as decimal))
                  and (cast(:maxPrice as decimal) is null or price <= cast(:maxPrice as decimal))
                  and (cast(:category as varchar) is null or lower(category) like '%' || lower(cast(:category as varchar)) || '%')
                  and (cast(:bodyType as varchar) is null or lower(body_type) like '%' || lower(cast(:bodyType as varchar)) || '%')
                  and (cast(:fuelType as varchar) is null or lower(fuel_type) like '%' || lower(cast(:fuelType as varchar)) || '%')
                order by year desc, model
                limit :limit
                """;
        return jdbc.sql(sql)
                .param("query", blankToNull(query.query()))
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("minPrice", query.minPrice())
                .param("maxPrice", query.maxPrice())
                .param("category", blankToNull(query.category()))
                .param("bodyType", blankToNull(query.bodyType()))
                .param("fuelType", blankToNull(query.fuelType()))
                .param("limit", query.limit())
                .query((rs, rowNum) -> new VehicleEvidence(
                        rs.getString("model"), rs.getInt("year"), null,
                        rs.getBigDecimal("price"), rs.getString("currency"),
                        rs.getString("model_page_url"), rs.getString("price_source_url"),
                        instant(rs, "observed_at"), List.of()))
                .list();
    }

    @Override
    public CatalogCandidateLists<VehicleEvidence> searchCandidates(VehicleSearchQuery query,
                                                                    CatalogQueryVector queryVector) {
        if (query == null) throw new IllegalArgumentException("vehicle search query is required");
        if (blankToNull(query.query()) == null) {
            return CatalogPort.super.searchCandidates(query, queryVector);
        }
        return retrieval.searchCandidates(query, queryVector);
    }

    @Override
    public Optional<VehicleEvidence> find(VehicleLookup lookup) {
        var model = blankToNull(lookup.model());
        if (model == null) {
            return Optional.empty();
        }
        var baseQuery = lookup.trim() == null || lookup.trim().isBlank()
                ? """
                  select model, year, null as trim,
                         case when price_source_url is not null and observed_at is not null then price end as price,
                         currency, model_page_url, null as comparison_url,
                         price_source_url, observed_at
                  from ford_read.vehicle_search_v1
                  where lower(model) = lower(:model)
                    and (cast(:year as integer) is null or year = cast(:year as integer))
                  order by year desc limit 1
                  """
                : """
                  select model, year, trim,
                         case when price_source_url is not null and observed_at is not null then price end as price,
                         currency, model_page_url, comparison_url,
                         price_source_url, observed_at
                  from ford_read.vehicle_variants_v2
                  where lower(model) = lower(:model)
                    and (cast(:year as integer) is null or year = cast(:year as integer))
                    and normalized_trim = :normalizedTrim
                  order by year desc limit 1
                  """;
        var row = jdbc.sql(baseQuery)
                .param("model", model)
                .param("year", lookup.year())
                .param("normalizedTrim", normalize(lookup.trim()))
                .query((rs, rowNum) -> new VehicleRow(
                        rs.getString("model"), rs.getInt("year"), rs.getString("trim"),
                        rs.getBigDecimal("price"), rs.getString("currency"),
                        rs.getString("model_page_url"), rs.getString("price_source_url"),
                        instant(rs, "observed_at"), rs.getString("comparison_url")))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        var vehicle = row.get();
        var facts = jdbc.sql("""
                select cast(variant_id as varchar) as variant_id, category, fact_type, fact_name,
                       fact_name_normalized, fact_value, value_numeric, fact_unit, source_url, observed_at
                from ford_read.vehicle_facts_v2
                where lower(model) = lower(:model) and year = :year
                  and ((cast(:trim as varchar) is null and trim is null)
                       or lower(trim) = lower(cast(:trim as varchar)))
                  and fact_value is not null
                  and source_url is not null and observed_at is not null
                order by case fact_type when 'specification' then 0 else 1 end, fact_name
                """)
                .param("model", vehicle.model())
                .param("year", vehicle.year())
                .param("trim", vehicle.trim())
                .query((rs, rowNum) -> new VehicleFact(
                        rs.getString("fact_name"), rs.getString("fact_value"), rs.getString("fact_unit"),
                        rs.getString("source_url"), instant(rs, "observed_at"), rs.getString("fact_type"),
                        rs.getString("variant_id"), rs.getString("category"), rs.getString("fact_name_normalized"),
                        rs.getBigDecimal("value_numeric")))
                .list();
        var images = jdbc.sql("""
                select image_url, alt_text, image_type, width, height, source_url, observed_at
                from ford_read.vehicle_images_v1
                where lower(model) = lower(:model) and year = :year
                  and ((cast(:trim as varchar) is null and trim is null)
                       or lower(trim) = lower(cast(:trim as varchar)))
                  and image_url is not null
                order by image_type nulls last, image_url
                """)
                .param("model", vehicle.model())
                .param("year", vehicle.year())
                .param("trim", vehicle.trim())
                .query((rs, rowNum) -> new VehicleImage(
                        rs.getString("image_url"), rs.getString("alt_text"), rs.getString("image_type"),
                        nullableInt(rs, "width"), nullableInt(rs, "height"), rs.getString("source_url"),
                        instant(rs, "observed_at")))
                .list();
        var technicalSheetUrl = technicalSheetUrl(vehicle.model(), vehicle.year());
        return Optional.of(new VehicleEvidence(vehicle.model(), vehicle.year(), vehicle.trim(),
                vehicle.price(), vehicle.currency(), vehicle.modelPageUrl(), vehicle.priceSourceUrl(),
                vehicle.observedAt(), facts, images, technicalSheetUrl, vehicle.comparisonUrl()));
    }

    private String technicalSheetUrl(String model, int year) {
        return jdbc.sql("""
                select source_url
                from ford_read.vehicle_facts_v1
                where lower(model) = lower(:model) and year = :year and trim is null
                  and fact_type = 'specification' and source_url is not null
                order by observed_at desc nulls last, source_url
                """)
                .param("model", model)
                .param("year", year)
                .query(String.class)
                .list()
                .stream()
                .filter(JdbcCatalogAdapter::isTechnicalSheetUrl)
                .findFirst()
                .orElse(null);
    }

    private static boolean isTechnicalSheetUrl(String sourceUrl) {
        try {
            var path = URI.create(sourceUrl).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).contains("ficha-tecnica");
        }
        catch (IllegalArgumentException invalidSourceUrl) {
            return false;
        }
    }

    @Override
    public PromotionPage promotions(PromotionQuery query) {
        var asOf = query.asOf() == null ? LocalDate.now(ZoneOffset.UTC) : query.asOf();
        var fromAndWhere = """
                from ford_read.promotions_v3
                where (cast(:model as varchar) is null or lower(model) like '%' || lower(cast(:model as varchar)) || '%')
                  and (cast(:modelYear as integer) is null or year = cast(:modelYear as integer))
                  and source_url is not null and observed_at is not null
                  and (not :activeOnly or (valid_from is null or valid_from <= :asOf)
                       and (valid_until is null or valid_until >= :asOf))
                """;
        var count = jdbc.sql("select count(*) " + fromAndWhere)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("activeOnly", query.activeOnly())
                .param("asOf", asOf)
                .query(Long.class)
                .single();
        var sql = """
                select promotion_id, model, year, title, description, discount_amount, discount_percent, cash_price,
                       currency, conditions, valid_from, valid_until, source_url, observed_at, model_page_url,
                       cast(benefits as varchar) as benefits_json, cast(term_conflict as varchar) as term_conflict_json,
                       case when valid_from is null or valid_until is null then 'unknown'
                            when valid_from > :asOf then 'upcoming'
                            when valid_until < :asOf then 'expired'
                            else 'active' end as validity_status
                """ + fromAndWhere + """
                order by valid_until nulls last, title
                limit :limit
                offset :offset
                """;
        var rows = jdbc.sql(sql)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("activeOnly", query.activeOnly())
                .param("asOf", asOf)
                .param("limit", query.limit())
                .param("offset", query.offset())
                .query((rs, rowNum) -> new PromotionEvidence(
                        rs.getString("promotion_id"), rs.getString("model"), nullableInt(rs, "year"), rs.getString("title"),
                        rs.getString("description"), rs.getBigDecimal("discount_amount"),
                        rs.getBigDecimal("discount_percent"), rs.getBigDecimal("cash_price"),
                        rs.getString("currency"), rs.getString("conditions"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                        rs.getString("validity_status"), rs.getString("source_url"), instant(rs, "observed_at"),
                        rs.getString("model_page_url"), json(rs, "benefits_json"), json(rs, "term_conflict_json")))
                .list();
        return new PromotionPage(count, query.offset(), query.limit(), rows);
    }

    @Override
    public List<OfferEvidence> offers(OfferQuery query) {
        var asOf = query.asOf() == null ? LocalDate.now(ZoneOffset.UTC) : query.asOf();
        var activeOnly = query.activeOnly() == null || query.activeOnly();
        return jdbc.sql("""
                select promotion_id, model, year, title, offer_type, provider, cash_price, monthly_payment,
                       down_payment_amount, down_payment_percent, term_months, months_without_interest,
                       annual_interest_rate, cat_percent, currency, valid_from, valid_until,
                       requires_credit_approval, conditions, source_url, model_page_url, observed_at,
                       cast(benefits as varchar) as benefits_json, cast(term_conflict as varchar) as term_conflict_json,
                       tax_rate_percent, opening_commission_rate_percent,
                       maximum_down_payment_percent, life_insurance_price,
                       case when valid_from is null or valid_until is null then 'unknown'
                            when valid_from > :asOf then 'upcoming'
                            when valid_until < :asOf then 'expired'
                            else 'active' end as validity_status
                from ford_read.offers_v2
                where (cast(:model as varchar) is null or lower(model) = lower(cast(:model as varchar)))
                  and (cast(:modelYear as integer) is null or year = cast(:modelYear as integer))
                  and source_url is not null and observed_at is not null
                  and (cast(:offerType as varchar) is null or offer_type = cast(:offerType as varchar))
                  and (cast(:provider as varchar) is null or lower(provider) = lower(cast(:provider as varchar)))
                  and (cast(:maxCashPrice as decimal) is null or cash_price <= cast(:maxCashPrice as decimal))
                  and (cast(:maxMonthlyPayment as decimal) is null or monthly_payment <= cast(:maxMonthlyPayment as decimal))
                  and (cast(:maxDownPayment as decimal) is null or down_payment_amount <= cast(:maxDownPayment as decimal))
                  and (cast(:minimumMonths as integer) is null or months_without_interest >= cast(:minimumMonths as integer))
                  and (cast(:termMonths as integer) is null or term_months = cast(:termMonths as integer))
                  and (not :activeOnly or (valid_from is null or valid_from <= :asOf)
                       and (valid_until is null or valid_until >= :asOf))
                order by valid_until nulls last, model, title
                limit :limit
                """)
                .param("model", blankToNull(query.model()))
                .param("modelYear", query.modelYear())
                .param("offerType", blankToNull(query.offerType()))
                .param("provider", blankToNull(query.provider()))
                .param("maxCashPrice", query.maxCashPrice())
                .param("maxMonthlyPayment", query.maxMonthlyPayment())
                .param("maxDownPayment", query.maxDownPayment())
                .param("minimumMonths", query.minimumMonthsWithoutInterest())
                .param("termMonths", query.termMonths())
                .param("activeOnly", activeOnly)
                .param("asOf", asOf)
                .param("limit", query.limit())
                .query((rs, rowNum) -> new OfferEvidence(
                        rs.getString("promotion_id"), rs.getString("model"), nullableInt(rs, "year"), rs.getString("title"),
                        rs.getString("offer_type"), rs.getString("provider"), rs.getBigDecimal("cash_price"),
                        rs.getBigDecimal("monthly_payment"), rs.getBigDecimal("down_payment_amount"),
                        rs.getBigDecimal("down_payment_percent"), nullableInt(rs, "term_months"),
                        nullableInt(rs, "months_without_interest"), rs.getBigDecimal("annual_interest_rate"),
                        rs.getBigDecimal("cat_percent"), rs.getString("currency"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                        rs.getString("validity_status"), rs.getBoolean("requires_credit_approval"),
                        rs.getString("conditions"), rs.getString("source_url"), rs.getString("model_page_url"),
                        instant(rs, "observed_at"), json(rs, "benefits_json"), json(rs, "term_conflict_json"),
                        rs.getBigDecimal("tax_rate_percent"), rs.getBigDecimal("opening_commission_rate_percent"),
                        rs.getBigDecimal("maximum_down_payment_percent"), rs.getBigDecimal("life_insurance_price")))
                .list();
    }

    @Override
    public CatalogCandidateLists<OfferEvidence> offerCandidates(OfferQuery query,
                                                                CatalogQueryVector queryVector) {
        if (query == null) throw new IllegalArgumentException("offer query is required");
        if (blankToNull(query.query()) == null) {
            return CatalogPort.super.offerCandidates(query, queryVector);
        }
        return retrieval.offerCandidates(query, queryVector);
    }

    @Override
    public CatalogPageLinkPage pageLinks(String sourceUrl, int offset, int limit, boolean includeAssets) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return new CatalogPageLinkPage(null, 0, Math.max(offset, 0), limit, List.of());
        }
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("page-link offset/limit is outside the supported range");
        }
        var total = jdbc.sql("""
                select count(distinct target_url)
                from ford_read.page_links_v1
                where source_page_url = :sourceUrl
                  and (:includeAssets or lower(target_url) not like '%.avif%'
                       and lower(target_url) not like '%.bmp%'
                       and lower(target_url) not like '%.gif%'
                       and lower(target_url) not like '%.jpg%'
                       and lower(target_url) not like '%.jpeg%'
                       and lower(target_url) not like '%.png%'
                       and lower(target_url) not like '%.webp%'
                       and lower(target_url) not like '%.svg%'
                       and lower(target_url) not like '%.ico%'
                       and lower(target_url) not like '%.css%'
                       and lower(target_url) not like '%.js%'
                       and lower(target_url) not like '%.woff%'
                       and lower(target_url) not like '%.ttf%'
                       and lower(target_url) not like '%.eot%'
                       and lower(target_url) not like '%.pdf%')
                """)
                .param("sourceUrl", sourceUrl)
                .param("includeAssets", includeAssets)
                .query(Long.class)
                .single();
        var knownSource = jdbc.sql("""
                select exists(select 1 from ford_read.catalog_pages_v1 where source_url = :sourceUrl)
                """)
                .param("sourceUrl", sourceUrl)
                .query(Boolean.class)
                .single();
        if (!knownSource) return new CatalogPageLinkPage(false, null, 0, offset, limit, List.of());
        var title = jdbc.sql("""
                select title from ford_read.catalog_pages_v1
                where source_url = :sourceUrl
                limit 1
                """)
                .param("sourceUrl", sourceUrl)
                .query(String.class)
                .optional()
                .orElse(null);
        var links = jdbc.sql("""
                with candidates as (
                  select source_page_url, source_page_type, href, target_url, anchor_text, rel,
                         is_internal, link_type, observed_at,
                         row_number() over (partition by target_url
                           order by (anchor_text is null), anchor_text) as target_rank
                  from ford_read.page_links_v1
                  where source_page_url = :sourceUrl
                    and (:includeAssets or lower(target_url) not like '%.avif%'
                         and lower(target_url) not like '%.bmp%'
                         and lower(target_url) not like '%.gif%'
                         and lower(target_url) not like '%.jpg%'
                         and lower(target_url) not like '%.jpeg%'
                         and lower(target_url) not like '%.png%'
                         and lower(target_url) not like '%.webp%'
                         and lower(target_url) not like '%.svg%'
                         and lower(target_url) not like '%.ico%'
                         and lower(target_url) not like '%.css%'
                         and lower(target_url) not like '%.js%'
                         and lower(target_url) not like '%.woff%'
                         and lower(target_url) not like '%.ttf%'
                         and lower(target_url) not like '%.eot%'
                         and lower(target_url) not like '%.pdf%')
                )
                select source_page_url, source_page_type, href, target_url, anchor_text, rel,
                       is_internal, link_type, observed_at
                from candidates where target_rank = 1
                order by case when lower(target_url) like '%/comparativo/%' then 0 else 1 end,
                         is_internal desc, (anchor_text is null), anchor_text, target_url
                limit :limit offset :offset
                """)
                .param("sourceUrl", sourceUrl)
                .param("includeAssets", includeAssets)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new CatalogPageLinkEvidence(
                        rs.getString("source_page_url"), rs.getString("source_page_type"),
                        rs.getString("href"), rs.getString("target_url"), rs.getString("anchor_text"),
                        rs.getString("rel"), rs.getBoolean("is_internal"), rs.getString("link_type"),
                        instant(rs, "observed_at")))
                .list();
        return new CatalogPageLinkPage(true, title, total, offset, limit, links);
    }

    @Override
    public Optional<DocumentProvenance> provenance(String documentId) {
        final UUID id;
        try {
            id = UUID.fromString(documentId);
        }
        catch (RuntimeException invalidId) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select document_id, content_hash, page_content_hash, title, document_type,
                       source_url, model_page_url, observed_at, model, year, trim
                from ford_read.document_provenance_v1 where document_id = :documentId
                """)
                .param("documentId", id)
                .query((rs, rowNum) -> new DocumentProvenance(
                        rs.getObject("document_id", UUID.class).toString(), rs.getString("content_hash"),
                        rs.getString("page_content_hash"), rs.getString("title"),
                        rs.getString("document_type"), rs.getString("source_url"),
                        rs.getString("model_page_url"), instant(rs, "observed_at"),
                        rs.getString("model"), (Integer) rs.getObject("year"), rs.getString("trim")))
                .optional();
    }

    @Override
    public CatalogStatus status() {
        return jdbc.sql("select * from ford_read.catalog_status_v1")
                .query((rs, rowNum) -> new CatalogStatus(
                        rs.getLong("model_count"), rs.getLong("active_model_year_count"),
                        rs.getLong("variant_count"), rs.getLong("page_count"),
                        rs.getLong("document_count"), rs.getLong("embedded_document_count"),
                        rs.getLong("pending_embedding_count"),
                        instant(rs, "latest_page_crawl_at"), rs.getString("latest_sync_status"),
                        instant(rs, "latest_sync_started_at"), instant(rs, "latest_sync_finished_at"),
                        rs.getLong("page_count"), rs.getLong("active_model_year_count"),
                        rs.getLong("embedded_document_count")))
                .single();
    }

    @Override
    public List<VariantComparisonEvidence> variantComparisons(String model, Integer year, String trim,
                                                                 int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("comparison offset/limit is outside the supported range");
        }
        return jdbc.sql("""
                select model, year, trim, competitor_make, competitor_model, competitor_year,
                       competitor_variant, category, fact_name, fact_value, fact_unit,
                       source_url, observed_at, comparison_url
                from ford_read.comparison_facts_v2
                where lower(model) = lower(:model)
                  and year = :year
                  and lower(trim) = lower(:trim)
                order by competitor_make, competitor_model, fact_name
                limit :limit offset :offset
                """)
                .param("model", model)
                .param("year", year)
                .param("trim", trim)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new VariantComparisonEvidence(
                        rs.getString("model"), nullableInt(rs, "year"), rs.getString("trim"),
                        rs.getString("competitor_make"), rs.getString("competitor_model"),
                        nullableInt(rs, "competitor_year"), rs.getString("competitor_variant"),
                        rs.getString("category"), rs.getString("fact_name"), rs.getString("fact_value"),
                        rs.getString("fact_unit"), rs.getString("source_url"), instant(rs, "observed_at"),
                        rs.getString("comparison_url")))
                .list();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> stringList(Object value) throws SQLException {
        if (value == null) return List.of();
        if (value instanceof Array sqlArray) {
            var raw = (Object[]) sqlArray.getArray();
            var values = new ArrayList<String>(raw.length);
            for (var item : raw) if (item != null) values.add(item.toString());
            return List.copyOf(values);
        }
        if (value instanceof String text && text.startsWith("[")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            }
            catch (java.io.IOException ignored) {
                return List.of();
            }
        }
        return List.of(value.toString());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        var value = rs.getString(column);
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        }
        catch (java.io.IOException invalidJson) {
            throw new IllegalStateException("Invalid JSON in catalog read view column " + column, invalidJson);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private record VehicleRow(String model, int year, String trim, BigDecimal price, String currency,
                              String modelPageUrl, String priceSourceUrl, Instant observedAt,
                              String comparisonUrl) { }
}
