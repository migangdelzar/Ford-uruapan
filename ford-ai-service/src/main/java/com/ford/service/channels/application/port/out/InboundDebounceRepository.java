package com.ford.service.channels.application.port.out;

import com.ford.service.channels.api.InboundMessageAccepted;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable per-conversation debounce schedule. One row per (tenant, conversation)
 * coalesces a burst into a single delayed response opportunity.
 */
public interface InboundDebounceRepository {

    /** Persist or coalesce the scheduled response opportunity for a conversation. */
    void schedule(InboundMessageAccepted event, Instant dueAt);

    /** True when a durable schedule exists and its due time has been reached. */
    boolean isDue(UUID tenantId, UUID conversationId, UUID receiptId, Instant now);

    /** Claim (and remove) the due schedules so their response opportunities can be enqueued. */
    List<InboundMessageAccepted> claimDue(Instant now, int limit);
}
