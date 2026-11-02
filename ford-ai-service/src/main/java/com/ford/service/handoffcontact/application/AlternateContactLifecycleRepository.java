package com.ford.service.handoffcontact.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlternateContactLifecycleRepository {
    List<TicketContactReference> findExpiredTicketContacts(Instant now, int limit);

    List<TicketContactReference> findTicketContacts(UUID tenantId, UUID conversationId);

    List<StagingContactReference> findStagingContacts(UUID tenantId, UUID conversationId);

    void fenceConversation(UUID tenantId, UUID conversationId, Instant forgottenAt);

    Optional<Instant> findContactExposure(UUID tenantId, UUID conversationId);

    void clearContactExposure(UUID tenantId, UUID conversationId, Instant expectedCapturedAt);

    List<ContactExposureReference> findExpiredContactExposures(Instant now, int limit);

    boolean clearTicketContact(UUID tenantId, UUID ticketId);

    record TicketContactReference(UUID tenantId, UUID ticketId, UUID conversationId,
                                  Instant collectedAt, Instant closedAt, Instant expiresAt) { }

    record StagingContactReference(UUID tenantId, UUID conversationId, UUID receiptId, Instant expiresAt) { }

    record ContactExposureReference(UUID tenantId, UUID conversationId, Instant capturedAt) { }
}
