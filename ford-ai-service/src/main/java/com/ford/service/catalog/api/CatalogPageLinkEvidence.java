package com.ford.service.catalog.api;

import java.time.Instant;

public record CatalogPageLinkEvidence(String sourcePageUrl, String sourcePageType, String href,
                                  String targetUrl, String anchorText, String rel,
                                  boolean internal, String linkType, Instant observedAt) {
}
