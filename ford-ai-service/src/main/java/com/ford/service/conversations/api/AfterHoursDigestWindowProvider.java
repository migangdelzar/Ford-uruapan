package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;

@NamedInterface
public interface AfterHoursDigestWindowProvider {
    AfterHoursDigestWindow getAfterHoursDigestWindow();

    boolean isOutsideBusinessHours(Instant instant);
}
