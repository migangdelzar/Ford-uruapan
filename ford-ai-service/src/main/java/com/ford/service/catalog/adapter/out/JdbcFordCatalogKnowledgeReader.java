package com.ford.service.catalog.adapter.out;

import com.ford.service.catalog.domain.CatalogDocumentHit;
import com.ford.service.catalog.domain.CatalogFact;
import com.ford.service.catalog.domain.CatalogFilters;
import com.ford.service.catalog.domain.CatalogModelSearchResult;
import com.ford.service.catalog.domain.CatalogQueryEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.UUID;

@Component
@ConditionalOnBean(name = {"fordCatalogJdbcClient", "catalogQueryEmbeddingModel"})
@NamedInterface
public final class JdbcFordCatalogKnowledgeReader {

    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_RESULTS = 8;
    private static final String DOCUMENT_FILTERS = """
            and (cast(:model as text) is null or d.normalized_model like '%' || lower(cast(:model as text)) || '%')
            and (cast(:modelYear as integer) is null or d.model_year_id is null or exists (
                select 1 from ford_catalog.vehicle_search_v1 sy
                where sy.model_year_id = d.model_year_id and sy.year = cast(:modelYear as integer)
            ))
            and (
                (cast(:modelYear as integer) is null
                 and cast(:minimumPrice as numeric) is null
                 and cast(:maximumPrice as numeric) is null
                 and cast(:color as text) is null
                 and cast(:category as text) is null
                 and cast(:bodyType as text) is null
                 and cast(:fuelType as text) is null)
                or exists (select 1 from eligible_variants ev where ev.model_id = d.model_id)
            )
            """;

    private final JdbcClient jdbc;
    private final CatalogQueryEmbeddingModel embeddingModel;

    public JdbcFordCatalogKnowledgeReader(
            @org.springframework.beans.factory.annotation.Qualifier("fordCatalogJdbcClient") JdbcClient jdbc,
            @org.springframework.beans.factory.annotation.Qualifier("catalogQueryEmbeddingModel")
            CatalogQueryEmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    public List<CatalogModelSearchResult> searchHybrid(String text, CatalogFilters filters, int limit) {
        if (text == null || text.isBlank() || limit <= 0) {
            return List.of();
        }
        var safeFilters = filters == null ? CatalogFilters.empty() : filters;
        var boundedLimit = Math.min(limit, MAX_RESULTS);
        var vector = toVectorLiteral(embeddingModel.embed(text));
        var rows = jdbc.sql("""
                        with model_identity as (
                            select d.model_id,
                                   min(s.normalized_name) as normalized_model,
                                   min(s.model) as model
                            from ford_catalog.catalog_documents_v1 d
                            join ford_catalog.vehicle_search_v1 s on s.model_year_id = d.model_year_id
                            where d.model_id is not null
                            group by d.model_id
                            having count(distinct s.normalized_name) = 1
                        ), resolved_documents as (
                            select d.document_id, d.document_type, d.title, d.semantic_content,
                                   d.model_year_id, d.variant_id, d.offer_id, d.source_url,
                                   d.embedding, d.embedding_model, d.embedding_status, d.search_vector,
                                   mi.model_id, mi.normalized_model, mi.model
                            from ford_catalog.catalog_documents_v1 d
                            join model_identity mi on mi.model_id = d.model_id
                            union all
                            select d.document_id, d.document_type, d.title, d.semantic_content,
                                   os.model_year_id, d.variant_id, d.offer_id, d.source_url,
                            d.embedding, d.embedding_model, d.embedding_status, d.search_vector,
                            mi.model_id, mi.normalized_model, mi.model
                            from ford_catalog.catalog_documents_v1 d
                            join ford_catalog.offers_v2 o on o.promotion_id = d.offer_id
                            join ford_catalog.vehicle_search_v1 os
                              on lower(btrim(os.model)) = lower(btrim(o.model)) and os.year = o.year
                            join model_identity mi on mi.normalized_model = os.normalized_name
                            where d.model_id is null and d.offer_id is not null
                        ), eligible_variants as (
                            select mi.model_id, s.model, s.year, s.model_year_id,
                                   v.variant_id, v.trim, v.price, v.currency,
                                   color.fact_value as color,
                                   coalesce(v.price_source_url, v.model_page_url) as source_url
                            from model_identity mi
                            join ford_catalog.vehicle_search_v1 s on s.normalized_name = mi.normalized_model
                            join ford_catalog.vehicle_variants_v2 v on v.model_year_id = s.model_year_id
                            left join lateral (
                                select f.fact_value
                                from ford_catalog.vehicle_facts_v2 f
                                where f.variant_id = v.variant_id
                                  and f.fact_name_normalized in ('color_exterior', 'exterior_color')
                                order by f.observed_at desc nulls last
                                limit 1
                            ) color on true
                            where (cast(:model as text) is null or mi.normalized_model like '%' || lower(cast(:model as text)) || '%')
                              and (cast(:modelYear as integer) is null or s.year = cast(:modelYear as integer))
                              and (cast(:minimumPrice as numeric) is null or v.price >= cast(:minimumPrice as numeric))
                              and (cast(:maximumPrice as numeric) is null or v.price <= cast(:maximumPrice as numeric))
                              and (cast(:color as text) is null or exists (
                                  select 1 from ford_catalog.vehicle_facts_v2 cf
                                  where cf.variant_id = v.variant_id
                                    and cf.fact_name_normalized in ('color_exterior', 'exterior_color')
                                    and position(lower(cast(:color as text)) in lower(cf.fact_value)) > 0
                              ))
                              and (cast(:category as text) is null or lower(s.category) = lower(cast(:category as text)))
                              and (cast(:bodyType as text) is null or lower(s.body_type) = lower(cast(:bodyType as text)))
                              and (cast(:fuelType as text) is null or lower(s.fuel_type) = lower(cast(:fuelType as text)))
                        ), vector_documents as (
                            select d.model_id, d.document_id,
                                   d.embedding OPERATOR(ford_catalog.<=>) cast(:embedding as ford_catalog.vector) as vector_distance,
                                   row_number() over (
                                       partition by d.model_id
                                        order by d.embedding OPERATOR(ford_catalog.<=>) cast(:embedding as ford_catalog.vector), d.document_id
                                   ) as model_document_rank
                            from resolved_documents d
                            where d.embedding is not null
                              and d.embedding_status = 'ready'
                              and d.embedding_model = 'bge-m3'
                        """ + DOCUMENT_FILTERS + """
                        ), vector_ranked as (
                            select model_id, document_id,
                                   row_number() over (order by vector_distance, model_id, document_id) as result_rank
                            from vector_documents
                            where model_document_rank = 1
                            order by vector_distance, model_id, document_id
                            limit :candidateLimit
                        ), keyword_documents as (
                            select d.model_id, d.document_id,
                                   ts_rank(d.search_vector, plainto_tsquery('spanish', :query)) as keyword_score,
                                   row_number() over (
                                       partition by d.model_id
                                       order by ts_rank(d.search_vector, plainto_tsquery('spanish', :query)) desc,
                                                d.document_id
                                   ) as model_document_rank
                            from resolved_documents d
                            where d.search_vector @@ plainto_tsquery('spanish', :query)
                        """ + DOCUMENT_FILTERS + """
                        ), keyword_ranked as (
                            select model_id, document_id,
                                   row_number() over (order by keyword_score desc, model_id, document_id) as result_rank
                            from keyword_documents
                            where model_document_rank = 1
                            order by keyword_score desc, model_id, document_id
                            limit :candidateLimit
                        ), fused_ranks as (
                            select model_id, document_id,
                                   1.0 / (60 + result_rank) as score, null::bigint as keyword_rank
                            from vector_ranked
                            union all
                            select model_id, document_id,
                                   1.0 / (60 + result_rank) as score, result_rank as keyword_rank
                            from keyword_ranked
                        ), model_scores as (
                            select model_id, sum(score) as score, min(keyword_rank) as keyword_rank
                            from fused_ranks
                            group by model_id
                        ), top_models as (
                            select mi.model_id, mi.model, ms.score, ms.keyword_rank
                            from model_scores ms
                            join model_identity mi on mi.model_id = ms.model_id
                            order by ms.score desc, ms.keyword_rank asc nulls last, mi.model_id
                            limit :resultLimit
                        ), evidence as (
                            select vr.model_id, vr.document_id
                            from vector_ranked vr join top_models tm on tm.model_id = vr.model_id
                            union
                            select kr.model_id, kr.document_id
                            from keyword_ranked kr join top_models tm on tm.model_id = kr.model_id
                        )
                        select m.model_id, m.model,
                               d.document_id as source_document_id, d.document_type as source_document_type,
                               d.title as source_title, d.semantic_content as source_content,
                               d.model_year_id as source_model_year_id, d.variant_id as source_variant_id,
                               d.offer_id as source_offer_id, d.source_url as source_url,
                               v.model_year_id as variant_model_year_id, v.variant_id as result_variant_id,
                               v.model as variant_model, v.year as variant_year, v.trim as variant_trim,
                               v.price as variant_price, v.currency as variant_currency,
                               v.color as variant_color, v.source_url as variant_source_url
                        from top_models m
                        join evidence e on e.model_id = m.model_id
                        join resolved_documents d on d.document_id = e.document_id
                        left join eligible_variants v on v.model_id = m.model_id
                        order by m.score desc, m.keyword_rank asc nulls last, m.model_id, d.document_id,
                                 v.year, v.price nulls last, v.trim
                        """)
                .param("query", text)
                .param("model", safeFilters.model())
                .param("modelYear", safeFilters.modelYear())
                .param("minimumPrice", safeFilters.minimumPrice())
                .param("maximumPrice", safeFilters.maximumPrice())
                .param("color", safeFilters.color())
                .param("category", safeFilters.category())
                .param("bodyType", safeFilters.bodyType())
                .param("fuelType", safeFilters.fuelType())
                .param("embedding", vector)
                .param("candidateLimit", MAX_CANDIDATES)
                .param("resultLimit", boundedLimit)
                .query((rs, rowNum) -> new HybridRow(
                        rs.getObject("model_id", UUID.class),
                        rs.getString("model"),
                        new CatalogDocumentHit(
                                rs.getObject("source_document_id", UUID.class),
                                rs.getString("source_document_type"),
                                rs.getString("source_title"),
                                rs.getString("source_content"),
                                rs.getObject("source_model_year_id", UUID.class),
                                rs.getObject("source_variant_id", UUID.class),
                                rs.getObject("source_offer_id", UUID.class),
                                rs.getString("source_url")),
                        rs.getObject("result_variant_id", UUID.class) == null ? null : new CatalogFact(
                                rs.getObject("variant_model_year_id", UUID.class),
                                rs.getObject("result_variant_id", UUID.class),
                                rs.getString("variant_model"),
                                rs.getInt("variant_year"),
                                rs.getString("variant_trim"),
                                rs.getBigDecimal("variant_price"),
                                rs.getString("variant_currency"),
                                rs.getString("variant_color"),
                                rs.getString("variant_source_url"))))
                .list();

        var grouped = new LinkedHashMap<UUID, ModelSearchAccumulator>();
        for (var row : rows) {
            grouped.computeIfAbsent(row.modelId(), id -> new ModelSearchAccumulator(id, row.model()))
                    .add(row);
        }
        return grouped.values().stream().map(ModelSearchAccumulator::toResult).toList();
    }

    private static String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length != OllamaCatalogQueryEmbeddingModel.DIMENSIONS) {
            throw new IllegalArgumentException("Catalog query vector must have exactly 1024 dimensions");
        }
        var literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            if (!Float.isFinite(vector[index])) {
                throw new IllegalArgumentException("Catalog query vector must contain only finite values");
            }
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }

    private record HybridRow(UUID modelId, String model, CatalogDocumentHit source, CatalogFact variant) { }

    private static final class ModelSearchAccumulator {
        private final UUID modelId;
        private final String model;
        private final LinkedHashMap<UUID, CatalogFact> variants = new LinkedHashMap<>();
        private final LinkedHashMap<UUID, CatalogDocumentHit> sources = new LinkedHashMap<>();

        private ModelSearchAccumulator(UUID modelId, String model) {
            this.modelId = modelId;
            this.model = model;
        }

        private void add(HybridRow row) {
            sources.putIfAbsent(row.source().documentId(), row.source());
            if (row.variant() != null) {
                variants.putIfAbsent(row.variant().variantId(), row.variant());
            }
        }

        private CatalogModelSearchResult toResult() {
            return new CatalogModelSearchResult(modelId, model, new ArrayList<>(variants.values()),
                    new ArrayList<>(sources.values()));
        }
    }

}
