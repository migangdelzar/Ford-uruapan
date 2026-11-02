package com.ford.service.catalog.domain;

import java.util.UUID;

public record CatalogDocumentHit(
        UUID documentId,
        String documentType,
        String title,
        String semanticContent,
        UUID modelYearId,
        UUID variantId,
        UUID offerId,
        String sourceUrl) {
}
