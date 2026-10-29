package com.ford.service.handoff.api;

import com.ford.service.handoff.domain.HandoffStatus;
import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@NamedInterface
public record AfterHoursDigest(UUID tenantId, LocalDate businessDate, String timeZone, boolean available,
                               Instant availableAt, Instant windowStart, Instant windowEnd, int totalCount,
                               int completePacketCount, int partialPacketCount, List<Item> items) {
    public AfterHoursDigest {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public record Item(UUID handoffId, UUID conversationId, Instant createdAt, HandoffStatus status,
                       String reason, int packetCompleteness, boolean packetPartial) { }
}
