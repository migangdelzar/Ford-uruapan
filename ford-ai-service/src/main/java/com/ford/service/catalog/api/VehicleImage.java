package com.ford.service.catalog.api;

import java.time.Instant;

/** A catalog image URL with its source page and crawl observation. */
public record VehicleImage(String url, String altText, String imageType,
                              Integer width, Integer height, String sourceUrl,
                              Instant observedAt) {
}
