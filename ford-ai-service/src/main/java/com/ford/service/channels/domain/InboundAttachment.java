package com.ford.service.channels.domain;

public record InboundAttachment(String providerMediaId, String declaredMimeType,
                                String filename, Long sizeBytes) {
    public InboundAttachment {
        if (providerMediaId == null || providerMediaId.isBlank()) {
            throw new IllegalArgumentException("provider media ID is required");
        }
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("attachment size cannot be negative");
        }
    }
}
