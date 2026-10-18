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

    Optional<VehicleEvidence> find(VehicleLookup lookup);

    PromotionPage promotions(PromotionQuery query);

    List<OfferEvidence> offers(OfferQuery query);

    CatalogPageLinkPage pageLinks(String sourceUrl, int offset, int limit, boolean includeAssets);

    Optional<DocumentProvenance> provenance(String documentId);

    CatalogStatus status();

    List<VariantComparisonEvidence> variantComparisons(String model, Integer year, String trim,
                                                          int offset, int limit);
}
