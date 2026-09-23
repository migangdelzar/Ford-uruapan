package com.ford.service.leads.api;

import java.util.List;

public record LeadPage(List<LeadView> items, String nextCursor, boolean hasNext) {
    public LeadPage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
