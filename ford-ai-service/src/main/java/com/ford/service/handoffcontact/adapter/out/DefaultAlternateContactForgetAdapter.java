package com.ford.service.handoffcontact.adapter.out;

import com.ford.service.handoffcontact.api.AlternateContactForgetPort;
import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.handoffcontact.api.StagingContext;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger.DeletionMarker;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger.DeletionReason;
import com.ford.service.handoffcontact.application.AlternateContactLifecycleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class DefaultAlternateContactForgetAdapter implements AlternateContactForgetPort {
    private final AlternateContactLifecycleRepository lifecycle;
    private final AlternateContactStagingPort staging;
    private final AlternateContactDeletionLedger ledger;

    public DefaultAlternateContactForgetAdapter(AlternateContactLifecycleRepository lifecycle,
                                                AlternateContactStagingPort staging,
                                                AlternateContactDeletionLedger ledger) {
        this.lifecycle = lifecycle;
        this.staging = staging;
        this.ledger = ledger;
    }

    @Override
    @Transactional
    public int forgetForConversation(UUID tenantId, UUID conversationId, Instant forgottenAt, String actorId) {
        if (tenantId == null || conversationId == null || forgottenAt == null)
            throw new IllegalArgumentException("tenant, conversation, and forgotten timestamp are required");
        lifecycle.fenceConversation(tenantId, conversationId, forgottenAt);
        var tickets = lifecycle.findTicketContacts(tenantId, conversationId);
        var staged = lifecycle.findStagingContacts(tenantId, conversationId);
        var exposureAt = lifecycle.findContactExposure(tenantId, conversationId);
        if (!tickets.isEmpty() || !staged.isEmpty() || exposureAt.isPresent()) {
            ledger.record(AlternateContactDeletionLedger.marker(tenantId, conversationId, null, null, forgottenAt,
                    DeletionReason.FORGET));
        }
        exposureAt.ifPresent(capturedAt -> lifecycle.clearContactExposure(tenantId, conversationId, capturedAt));
        for (var ticket : tickets) {
            ledger.record(AlternateContactDeletionLedger.marker(tenantId, conversationId, ticket.ticketId(), null, forgottenAt,
                    DeletionReason.FORGET));
        }
        for (var row : staged) {
            ledger.record(AlternateContactDeletionLedger.marker(tenantId, conversationId, null, row.receiptId(), forgottenAt,
                    DeletionReason.FORGET));
        }
        var removed = 0;
        for (var ticket : tickets) if (lifecycle.clearTicketContact(tenantId, ticket.ticketId())) removed++;
        for (var row : staged) if (staging.delete(tenantId, conversationId, row.receiptId())) removed++;
        return removed;
    }

}
