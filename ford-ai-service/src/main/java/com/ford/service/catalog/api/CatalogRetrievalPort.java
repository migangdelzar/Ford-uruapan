package com.ford.service.catalog.api;

import org.springframework.modulith.NamedInterface;

@NamedInterface
public interface CatalogRetrievalPort {

    CatalogSearchPage<VehicleEvidence> searchVehicles(VehicleSearchQuery query);

    CatalogSearchPage<OfferEvidence> searchOffers(OfferQuery query);
}
