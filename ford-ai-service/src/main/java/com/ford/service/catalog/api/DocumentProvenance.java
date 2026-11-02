package com.ford.service.catalog.api;

import java.time.Instant;

public record DocumentProvenance(String documentId, String contentHash, String pageContentHash,
                                    String title, String documentType, String sourceUrl,
                                    String modelPageUrl, Instant observedAt, String model,
                                    Integer year, String trim) {
}
