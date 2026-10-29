package com.ford.service.catalog.domain;

import com.ford.service.shared.FreshnessStatus;

import java.time.Instant;

public record InventoryResult(int availableUnits, FreshnessStatus freshnessStatus, Instant observedAt) {
}
