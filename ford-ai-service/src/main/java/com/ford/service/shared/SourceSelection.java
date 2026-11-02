package com.ford.service.shared;

public record SourceSelection(SourceKind sourceKind, String payload, SourceMetadata metadata) {
}
