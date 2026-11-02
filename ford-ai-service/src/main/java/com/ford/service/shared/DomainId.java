package com.ford.service.shared;

import java.util.Objects;
import java.util.UUID;

public record DomainId(UUID value) {

    public DomainId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DomainId random() {
        return new DomainId(UUID.randomUUID());
    }
}
