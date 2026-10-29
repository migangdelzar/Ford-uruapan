package com.ford.service.dealership.application.port.out;

import java.util.List;
import java.util.UUID;

public interface InventoryPort {
    List<DealershipPort.InventoryItem> inventory(UUID tenantId, DealershipPort.InventoryFilter filter);

    DealershipPort.VehicleDetails vehicle(UUID tenantId, String vin);
}
