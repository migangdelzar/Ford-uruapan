package com.ford.service.catalog.application;

import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.api.InventoryQuery;
import com.ford.service.catalog.application.port.out.VehicleCatalogRepository;
import com.ford.service.catalog.domain.InventoryResult;
import com.ford.service.catalog.domain.VehicleDetails;
import com.ford.service.catalog.domain.VehicleSummary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultVehicleCatalog implements VehicleCatalog {

    private final VehicleCatalogRepository repository;

    public DefaultVehicleCatalog(VehicleCatalogRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public List<VehicleSummary> search(CatalogQuery query) {
        Objects.requireNonNull(query);
        if (query.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return repository.search(query);
    }

    @Override
    public Optional<VehicleDetails> findDetails(UUID vehicleId) {
        return repository.findDetails(Objects.requireNonNull(vehicleId));
    }

    @Override
    public InventoryResult inventory(InventoryQuery query) {
        Objects.requireNonNull(query);
        if (query.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return repository.inventory(query);
    }
}
