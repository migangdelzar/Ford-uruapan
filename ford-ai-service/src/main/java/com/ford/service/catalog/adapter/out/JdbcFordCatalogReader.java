package com.ford.service.catalog.adapter.out;

import com.ford.service.catalog.domain.CatalogFact;
import com.ford.service.catalog.domain.CatalogFilters;
import com.ford.service.catalog.domain.CatalogOffer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnBean(name = "fordCatalogJdbcClient")
@NamedInterface
public final class JdbcFordCatalogReader {

    private static final int MAX_RESULTS = 100;
    private static final String VARIANT_SQL = """
            select v.model_year_id, v.variant_id, v.model, v.year, v.trim, v.price, v.currency,
                   color.fact_value as color, coalesce(v.price_source_url, v.model_page_url) as source_url
            from ford_catalog.vehicle_variants_v2 v
            join ford_catalog.vehicle_search_v1 s on s.model_year_id = v.model_year_id
            left join lateral (
                select f.fact_value
                from ford_catalog.vehicle_facts_v2 f
                where f.variant_id = v.variant_id
                  and f.fact_name_normalized in ('color_exterior', 'exterior_color')
                order by f.observed_at desc nulls last
                limit 1
            ) color on true
            """;
    private final JdbcClient jdbc;

    public JdbcFordCatalogReader(@org.springframework.beans.factory.annotation.Qualifier("fordCatalogJdbcClient")
                                 JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CatalogFact> searchStructuredCatalog(CatalogFilters filters, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var boundedLimit = Math.min(limit, MAX_RESULTS);
        return jdbc.sql("""
                        select v.model_year_id, v.variant_id, v.model, v.year, v.trim, v.price, v.currency,
                               color.fact_value as color, coalesce(v.price_source_url, v.model_page_url) as source_url
                        from ford_catalog.vehicle_variants_v2 v
                        join ford_catalog.vehicle_search_v1 s on s.model_year_id = v.model_year_id
                        left join lateral (
                            select f.fact_value
                            from ford_catalog.vehicle_facts_v2 f
                            where f.variant_id = v.variant_id
                              and f.fact_name_normalized in ('color_exterior', 'exterior_color')
                            order by f.observed_at desc nulls last
                            limit 1
                        ) color on true
                        where (cast(:model as text) is null or v.normalized_model like '%' || lower(cast(:model as text)) || '%')
                          and (cast(:modelYear as integer) is null or v.year = cast(:modelYear as integer))
                          and (cast(:minimumPrice as numeric) is null or v.price >= cast(:minimumPrice as numeric))
                          and (cast(:maximumPrice as numeric) is null or v.price <= cast(:maximumPrice as numeric))
                          and (cast(:category as text) is null or lower(s.category) = lower(cast(:category as text)))
                          and (cast(:bodyType as text) is null or lower(s.body_type) = lower(cast(:bodyType as text)))
                          and (cast(:fuelType as text) is null or lower(s.fuel_type) = lower(cast(:fuelType as text)))
                          and (cast(:color as text) is null or exists (
                              select 1 from ford_catalog.vehicle_facts_v2 cf
                              where cf.variant_id = v.variant_id
                                and cf.fact_name_normalized in ('color_exterior', 'exterior_color')
                                and position(lower(cast(:color as text)) in lower(cf.fact_value)) > 0
                          ))
                        order by v.model, v.year, v.price nulls last, v.trim
                        limit :limit
                        """)
                .param("model", filters.model())
                .param("modelYear", filters.modelYear())
                .param("minimumPrice", filters.minimumPrice())
                .param("maximumPrice", filters.maximumPrice())
                .param("category", filters.category())
                .param("bodyType", filters.bodyType())
                .param("fuelType", filters.fuelType())
                .param("color", filters.color())
                .param("limit", boundedLimit)
                .query((rs, rowNum) -> new CatalogFact(
                        rs.getObject("model_year_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("model"),
                        rs.getInt("year"),
                        rs.getString("trim"),
                        rs.getBigDecimal("price"),
                        rs.getString("currency"),
                        rs.getString("color"),
                        rs.getString("source_url")))
                .list();
    }

    public Optional<CatalogFact> getCatalogVariant(UUID variantId) {
        return jdbc.sql(VARIANT_SQL + " where v.variant_id = :variantId")
                .param("variantId", variantId)
                .query((rs, rowNum) -> toCatalogFact(rs))
                .optional();
    }

    public List<CatalogOffer> listCurrentOffers(UUID modelYearId) {
        return jdbc.sql("""
                        select o.promotion_id, o.model, o.year, o.title, o.description, o.cash_price,
                               o.currency, o.valid_from, o.valid_until, o.source_url
                        from ford_catalog.offers_v2 o
                        where exists (
                            select 1 from ford_catalog.vehicle_search_v1 s
                            where s.model_year_id = :modelYearId
                              and lower(s.model) = lower(o.model)
                              and s.year = o.year
                        )
                          and (o.valid_from is null or o.valid_from <= current_date)
                          and (o.valid_until is null or o.valid_until >= current_date)
                        order by o.valid_until nulls last, o.title
                        limit :limit
                        """)
                .param("modelYearId", modelYearId)
                .param("limit", MAX_RESULTS)
                .query((rs, rowNum) -> new CatalogOffer(
                        rs.getObject("promotion_id", UUID.class),
                        rs.getString("model"),
                        rs.getInt("year"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getBigDecimal("cash_price"),
                        rs.getString("currency"),
                        rs.getObject("valid_from", java.time.LocalDate.class),
                        rs.getObject("valid_until", java.time.LocalDate.class),
                        rs.getString("source_url")))
                .list();
    }

    private static CatalogFact toCatalogFact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CatalogFact(
                rs.getObject("model_year_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getString("model"),
                rs.getInt("year"),
                rs.getString("trim"),
                rs.getBigDecimal("price"),
                rs.getString("currency"),
                rs.getString("color"),
                rs.getString("source_url"));
    }
}
