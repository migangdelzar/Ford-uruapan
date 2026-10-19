package com.ford.service.catalog.api;

import com.ford.service.catalog.api.PromotionEvidence;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.PromotionPage;
import com.ford.service.catalog.api.OfferEvidence;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.CatalogPageLinkEvidence;
import com.ford.service.catalog.api.DocumentProvenance;
import com.ford.service.catalog.api.CatalogStatus;
import com.ford.service.catalog.api.VariantComparisonEvidence;
import com.ford.service.catalog.api.CatalogPageLinkPage;
import com.ford.service.catalog.api.VehicleEvidence;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleSearchQuery;

import java.util.List;
import java.util.Optional;

/** Read-only boundary for Ford-scraping catalog facts used by Spring AI tools. */
public interface CatalogPort {

    List<VehicleEvidence> search(VehicleSearchQuery query);

    default CatalogCandidateLists<VehicleEvidence> searchCandidates(
            VehicleSearchQuery query, CatalogQueryVector queryVector) {
        var lexical = search(query).stream()
                .map(evidence -> new CatalogRetrievalCandidate<>(vehicleStableId(evidence),
                        vehicleSearchText(evidence), evidence))
                .toList();
        return new CatalogCandidateLists<>(lexical, List.of(), List.of(), "unavailable");
    }

    Optional<VehicleEvidence> find(VehicleLookup lookup);

    PromotionPage promotions(PromotionQuery query);

    List<OfferEvidence> offers(OfferQuery query);

    default CatalogCandidateLists<OfferEvidence> offerCandidates(OfferQuery query, CatalogQueryVector queryVector) {
        var lexical = offers(query).stream()
                .map(evidence -> new CatalogRetrievalCandidate<>(offerStableId(evidence),
                        offerSearchText(evidence), evidence))
                .toList();
        return new CatalogCandidateLists<>(lexical, List.of(), List.of(), "unavailable");
    }

    CatalogPageLinkPage pageLinks(String sourceUrl, int offset, int limit, boolean includeAssets);

    Optional<DocumentProvenance> provenance(String documentId);

    CatalogStatus status();

    List<VariantComparisonEvidence> variantComparisons(String model, Integer year, String trim,
                                                          int offset, int limit);

    private static String vehicleStableId(VehicleEvidence evidence) {
        return "vehicle:" + String.join("|",
                evidence.model() == null ? "" : evidence.model().toLowerCase(java.util.Locale.ROOT),
                evidence.year() == null ? "" : evidence.year().toString(),
                evidence.trim() == null ? "" : evidence.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static String offerStableId(OfferEvidence evidence) {
        if (evidence.offerId() == null || evidence.offerId().isBlank()) {
            throw new IllegalArgumentException("stable offer id is required for retrieval");
        }
        return "offer:" + evidence.offerId();
    }

    private static String vehicleSearchText(VehicleEvidence evidence) {
        return java.util.stream.Stream.of(evidence.model(),
                        evidence.year() == null ? null : evidence.year().toString(), evidence.trim())
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String offerSearchText(OfferEvidence evidence) {
        return java.util.stream.Stream.of(evidence.model(),
                        evidence.modelYear() == null ? null : evidence.modelYear().toString(),
                        evidence.title(), evidence.offerType(), evidence.provider(), evidence.conditions())
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
