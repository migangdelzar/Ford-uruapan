package com.ford.service.catalog;

import com.ford.service.catalog.application.DefaultVehicleCatalog;
import com.ford.service.catalog.application.port.out.VehicleCatalogRepository;
import com.ford.service.catalog.api.CatalogQuery;
import com.ford.service.catalog.api.InventoryQuery;
import com.ford.service.catalog.domain.InventoryResult;
import com.ford.service.catalog.domain.VehicleSummary;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleCatalogTest {

    @Test
    void returnsVehicleSummariesAndMarksStaleInventory() {
        var repository = new InMemoryVehicleCatalogRepository();
        repository.vehicles = List.of(new VehicleSummary(
                UUID.randomUUID(), "Ranger", "XLT", 2026, List.of("Azul"),
                new Money(new java.math.BigDecimal("799000.00"), java.util.Currency.getInstance("MXN")),
                FreshnessStatus.FRESH));
        repository.inventory = new InventoryResult(2, FreshnessStatus.STALE, Instant.parse("2026-09-01T00:00:00Z"));
        var catalog = new DefaultVehicleCatalog(repository);

        assertThat(catalog.search(new CatalogQuery(UUID.randomUUID(), "ranger", null, null)))
                .singleElement().extracting(VehicleSummary::modelName).isEqualTo("Ranger");
        assertThat(catalog.inventory(new InventoryQuery(UUID.randomUUID(), "XLT", "Uruapan"))
                .freshnessStatus()).isEqualTo(FreshnessStatus.STALE);
    }

    private static final class InMemoryVehicleCatalogRepository implements VehicleCatalogRepository {
        private List<VehicleSummary> vehicles = List.of();
        private InventoryResult inventory = new InventoryResult(0, FreshnessStatus.UNKNOWN, Instant.now());

        @Override
        public List<VehicleSummary> search(CatalogQuery query) {
            return vehicles;
        }

        @Override
        public InventoryResult inventory(InventoryQuery query) {
            return inventory;
        }

        @Override
        public Optional<com.ford.service.catalog.domain.VehicleDetails> findDetails(java.util.UUID vehicleId) {
            return Optional.empty();
        }
    }
}
