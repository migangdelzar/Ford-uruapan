package com.ford.service.handoff.api;

import com.ford.service.handoff.domain.HandoffPriority;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Ordinary queue representation. Alternate-contact values and preference are reveal-only. */
public record HandoffTicketView(UUID id, UUID tenantId, UUID conversationId, UUID leadId,
                                HandoffStatus status, HandoffPriority priority, UUID assignedOperatorId,
                                String reason, long version, Instant createdAt, Instant claimedAt, Instant closedAt,
                                Map<String, String> packet) {
    public HandoffTicketView {
        packet = Map.copyOf(packet == null ? Map.of() : packet);
    }

    public static HandoffTicketView from(HandoffTicket ticket) {
        var safePacket = ticket.packet().arguments().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("alternateContact"))
                .filter(entry -> !entry.getKey().equals("preferredContactChannel"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return new HandoffTicketView(ticket.id(), ticket.tenantId(), ticket.conversationId(), ticket.leadId(),
                ticket.status(), ticket.priority(), ticket.assignedOperatorId(), ticket.reason(), ticket.version(),
                ticket.createdAt(), ticket.claimedAt(), ticket.closedAt(), safePacket);
    }
}
