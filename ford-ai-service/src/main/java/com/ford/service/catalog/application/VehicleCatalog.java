package com.ford.service.catalog.application;

import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.api.InventoryQuery;
import com.ford.service.catalog.domain.InventoryResult;
import com.ford.service.catalog.domain.VehicleDetails;
import com.ford.service.catalog.domain.VehicleSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleCatalog {

    List<VehicleSummary> search(CatalogQuery query);

    Optional<VehicleDetails> findDetails(UUID vehicleId);

    InventoryResult inventory(InventoryQuery query);
}
