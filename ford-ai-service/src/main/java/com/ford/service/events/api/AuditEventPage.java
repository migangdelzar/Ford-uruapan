package com.ford.service.events.api;

import java.util.List;

public record AuditEventPage(List<AuditEventView> items, String nextCursor, boolean hasNext) {
    public AuditEventPage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
