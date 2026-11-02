package com.ford.service.catalog.api;

import java.util.UUID;

public record CatalogQuery(UUID tenantId, String text, Integer modelYear, String color) {
}
