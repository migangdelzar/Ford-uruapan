package com.ford.service.catalog.api;

import java.util.UUID;

public record InventoryQuery(UUID tenantId, String variantCode, String dealershipCode) {
}
