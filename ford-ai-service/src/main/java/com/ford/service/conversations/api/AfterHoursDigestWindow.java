package com.ford.service.conversations.api;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.time.LocalDate;

@NamedInterface
public record AfterHoursDigestWindow(LocalDate businessDate, String timeZone, Instant availableAt,
                                    Instant windowStart, Instant windowEnd, boolean available) {
}
