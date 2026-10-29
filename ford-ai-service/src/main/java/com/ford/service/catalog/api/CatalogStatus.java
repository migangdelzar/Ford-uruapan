package com.ford.service.catalog.api;

import java.time.Instant;

public record CatalogStatus(long modelCount, long activeModelYearCount, long variantCount,
                               long pageCount, long documentCount, long embeddedDocumentCount,
                               long pendingEmbeddingCount, Instant latestPageCrawlAt, String latestSyncStatus,
                               Instant latestSyncStartedAt, Instant latestSyncFinishedAt,
                               long pages, long vehicles, long embedded) {
}
