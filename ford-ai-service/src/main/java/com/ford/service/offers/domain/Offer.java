package com.ford.service.offers.domain;

import java.time.Instant;
import java.util.UUID;

public record Offer(UUID id, String code, String title, Instant effectiveFrom, Instant effectiveTo) {
}
