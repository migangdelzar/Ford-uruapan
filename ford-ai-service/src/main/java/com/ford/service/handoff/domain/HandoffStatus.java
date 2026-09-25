package com.ford.service.handoff.domain;

import org.springframework.modulith.NamedInterface;

@NamedInterface
public enum HandoffStatus {
    HUMAN_PENDING,
    HUMAN_ACTIVE,
    CLOSED
}
