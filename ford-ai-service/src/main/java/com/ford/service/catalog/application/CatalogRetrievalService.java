package com.ford.service.catalog.application;

import com.ford.service.catalog.api.CatalogCandidateLists;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogQueryEmbedder;
import com.ford.service.catalog.api.CatalogQueryVector;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import com.ford.service.catalog.api.CatalogRetrievalCandidate;
import com.ford.service.catalog.api.CatalogSearchPage;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.knowledge.application.ReciprocalRankFusion;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Combines independently ranked catalog candidates while keeping structured evidence authoritative. */
public final class CatalogRetrievalService implements CatalogRetrievalPort {

    private static final String UNAVAILABLE = "unavailable";
    private static final String NOT_REQUESTED = "not_requested";

    private final CatalogPort catalog;
    private final CatalogQueryEmbedder queryEmbedder;
    private final ReciprocalRankFusion fusion;

    public CatalogRetrievalService(CatalogPort catalog, CatalogQueryEmbedder queryEmbedder,
                                   ReciprocalRankFusion fusion) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog port is required");
        this.queryEmbedder = queryEmbedder;
        this.fusion = java.util.Objects.requireNonNull(fusion, "rank fusion is required");
    }

    @Override
    public CatalogSearchPage<VehicleEvidence> searchVehicles(VehicleSearchQuery query) {
        java.util.Objects.requireNonNull(query, "vehicle search query is required");
        if (isBlank(query.query())) {
            var results = catalog.search(query);
            return new CatalogSearchPage<>(NOT_REQUESTED, results.size(), results);
        }
        var queryVector = embed(query.query());
        var candidates = catalog.searchCandidates(query, queryVector.orElse(null));
        return vehiclePage(candidates, query.limit(), queryVector.isPresent());
    }

    @Override
    public CatalogSearchPage<OfferEvidence> searchOffers(OfferQuery query) {
        java.util.Objects.requireNonNull(query, "offer search query is required");
        if (isBlank(query.query())) {
            var results = catalog.offers(query);
            return new CatalogSearchPage<>(NOT_REQUESTED, results.size(), results);
        }
        var queryVector = embed(query.query());
        var candidates = catalog.offerCandidates(query, queryVector.orElse(null));
        return offerPage(candidates, query.limit(), queryVector.isPresent());
    }

    private Optional<CatalogQueryVector> embed(String query) {
        if (queryEmbedder == null) return Optional.empty();
        try {
            return Optional.ofNullable(queryEmbedder.embed(query)).orElse(Optional.empty());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private CatalogSearchPage<VehicleEvidence> vehiclePage(
            CatalogCandidateLists<VehicleEvidence> candidates, int limit, boolean vectorAvailable) {
        var ranked = fusion.mergeById(candidateLists(candidates), CatalogRetrievalCandidate::stableId,
                candidateLimit(limit));
        var state = vectorAvailable && "ready".equals(candidates.semanticSearch()) ? "ready" : UNAVAILABLE;
        var results = distinctVehicles(ranked, limit);
        return new CatalogSearchPage<>(state, results.size(), results);
    }

    private CatalogSearchPage<OfferEvidence> offerPage(
            CatalogCandidateLists<OfferEvidence> candidates, int limit, boolean vectorAvailable) {
        var ranked = fusion.mergeById(candidateLists(candidates), CatalogRetrievalCandidate::stableId,
                candidateLimit(limit));
        var state = vectorAvailable && "ready".equals(candidates.semanticSearch()) ? "ready" : UNAVAILABLE;
        var results = distinctOffers(ranked, limit);
        return new CatalogSearchPage<>(state, results.size(), results);
    }

    private static List<VehicleEvidence> distinctVehicles(
            List<CatalogRetrievalCandidate<VehicleEvidence>> ranked, int limit) {
        Set<String> seen = new HashSet<>();
        List<VehicleEvidence> results = new ArrayList<>();
        for (var candidate : ranked) {
            var evidence = candidate.evidence();
            var key = normalize(evidence.model()) + "|" + evidence.year() + "|" + normalize(evidence.trim());
            if (seen.add(key)) results.add(evidence);
            if (results.size() == limit) break;
        }
        return List.copyOf(results);
    }

    private static List<OfferEvidence> distinctOffers(
            List<CatalogRetrievalCandidate<OfferEvidence>> ranked, int limit) {
        Set<String> seen = new HashSet<>();
        List<OfferEvidence> results = new ArrayList<>();
        for (var candidate : ranked) {
            var evidence = candidate.evidence();
            if (seen.add(evidence.offerId())) results.add(evidence);
            if (results.size() == limit) break;
        }
        return List.copyOf(results);
    }

    private static int candidateLimit(int resultLimit) {
        return Math.min(40, Math.max(10, resultLimit * 4));
    }

    private static <T> List<List<CatalogRetrievalCandidate<T>>> candidateLists(
            CatalogCandidateLists<T> candidates) {
        return List.of(candidates.fullText(), candidates.trigram(), candidates.vector());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
