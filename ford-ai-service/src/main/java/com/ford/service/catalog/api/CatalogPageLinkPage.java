package com.ford.service.catalog.api;

import java.util.List;

public record CatalogPageLinkPage(boolean knownSource, String title, long totalLinks, int offset, int limit,
                              List<CatalogPageLinkEvidence> links) {
    public CatalogPageLinkPage {
        links = List.copyOf(links);
    }

    public CatalogPageLinkPage(String title, long totalLinks, int offset, int limit, List<CatalogPageLinkEvidence> links) {
        this(title != null, title, totalLinks, offset, limit, links);
    }
}
