package com.ford.service.handoffcontact.application;

import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.handoffcontact.api.StagingContext;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger.DeletionReason;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class AlternateContactRetentionJob {
    private final AlternateContactLifecycleRepository lifecycle;
    private final AlternateContactStagingPort staging;
    private final AlternateContactDeletionLedger ledger;

    public AlternateContactRetentionJob(AlternateContactLifecycleRepository lifecycle,
                                        AlternateContactStagingPort staging,
                                        AlternateContactDeletionLedger ledger) {
        this.lifecycle = lifecycle;
        this.staging = staging;
        this.ledger = ledger;
    }

    @Scheduled(fixedDelayString = "${ford.alternate-contact.retention-delay-ms:3600000}")
    @Transactional
    public void purgeExpired() {
        var now = Instant.now();
        for (var ticket : lifecycle.findExpiredTicketContacts(now, 250)) {
            ledger.record(AlternateContactDeletionLedger.marker(ticket.tenantId(), ticket.conversationId(),
                    ticket.ticketId(), null, now, DeletionReason.RETENTION));
            lifecycle.clearTicketContact(ticket.tenantId(), ticket.ticketId());
        }
        for (var row : staging.findExpired(now, 250)) {
            ledger.record(AlternateContactDeletionLedger.marker(row.tenantId(), row.conversationId(),
                    null, row.receiptId(), now, DeletionReason.STAGING_EXPIRY));
            staging.delete(row.tenantId(), row.conversationId(), row.receiptId());
        }
        for (var exposure : lifecycle.findExpiredContactExposures(now, 250)) {
            lifecycle.clearContactExposure(exposure.tenantId(), exposure.conversationId(), exposure.capturedAt());
        }
    }
}
