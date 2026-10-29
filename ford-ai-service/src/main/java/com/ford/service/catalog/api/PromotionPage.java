package com.ford.service.catalog.api;

import java.util.List;

public record PromotionPage(long totalCount, int offset, int limit, List<PromotionEvidence> results) {
    public PromotionPage {
        if (totalCount < 0 || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("invalid promotion page metadata");
        }
        results = List.copyOf(results);
    }

    public boolean hasMore() {
        return (long) offset + results.size() < totalCount;
    }
}
