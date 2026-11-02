package com.ford.service.handoffcontact.application;

import com.ford.service.handoffcontact.api.AlternateContactStagingPort;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger.DeletionMarker;
import com.ford.service.handoffcontact.application.AlternateContactDeletionLedger.DeletionReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlternateContactRetentionJobTest {
    @Test
    void writesTicketAndStagingMarkersBeforeClearingLiveRows() {
        var lifecycle = mock(AlternateContactLifecycleRepository.class);
        var staging = mock(AlternateContactStagingPort.class);
        var ledger = mock(AlternateContactDeletionLedger.class);
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var now = Instant.parse("2026-10-23T20:00:00Z");
        when(lifecycle.findExpiredTicketContacts(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new AlternateContactLifecycleRepository.TicketContactReference(tenant, ticketId, conversation,
                        now.minusSeconds(130L * 86400), null, now.minusSeconds(10))));
        when(staging.findExpired(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new AlternateContactStagingPort.ExpiredStagingReference(tenant, conversation, receipt,
                        now.minusSeconds(10))));
        var job = new AlternateContactRetentionJob(lifecycle, staging, ledger);

        job.purgeExpired();

        var order = inOrder(ledger, lifecycle, staging);
        var markers = org.mockito.ArgumentCaptor.forClass(DeletionMarker.class);
        order.verify(ledger).record(markers.capture());
        assertThat(markers.getValue().ticketId()).isEqualTo(ticketId);
        assertThat(markers.getValue().reason()).isEqualTo(DeletionReason.RETENTION);
        order.verify(lifecycle).clearTicketContact(tenant, ticketId);
        order.verify(ledger).record(markers.capture());
        assertThat(markers.getAllValues()).last().satisfies(marker -> {
            assertThat(marker.receiptId()).isEqualTo(receipt);
            assertThat(marker.reason()).isEqualTo(DeletionReason.STAGING_EXPIRY);
        });
        order.verify(staging).delete(tenant, conversation, receipt);
    }

    @Test
    void purgesExpiredExposureMetadataWithoutDeletingStillRetainedTicketContacts() {
        var lifecycle = mock(AlternateContactLifecycleRepository.class);
        var staging = mock(AlternateContactStagingPort.class);
        var ledger = mock(AlternateContactDeletionLedger.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var collectedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(lifecycle.findExpiredTicketContacts(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(staging.findExpired(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(lifecycle.findExpiredContactExposures(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new AlternateContactLifecycleRepository.ContactExposureReference(tenantId, conversationId, collectedAt)));

        var job = new AlternateContactRetentionJob(lifecycle, staging, ledger);
        var order = inOrder(lifecycle);

        job.purgeExpired();

        order.verify(lifecycle).clearContactExposure(tenantId, conversationId, collectedAt);
        org.mockito.Mockito.verifyNoInteractions(ledger);
    }

    @Test
    void concurrentFreshExposureIsNotRemovedUsingStaleExpiryReference() {
        var lifecycle = mock(AlternateContactLifecycleRepository.class);
        var staging = mock(AlternateContactStagingPort.class);
        var ledger = mock(AlternateContactDeletionLedger.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var expiredCapture = Instant.parse("2026-01-01T00:00:00Z");
        when(lifecycle.findExpiredTicketContacts(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(staging.findExpired(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(lifecycle.findExpiredContactExposures(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new AlternateContactLifecycleRepository.ContactExposureReference(tenantId, conversationId, expiredCapture)));

        new AlternateContactRetentionJob(lifecycle, staging, ledger).purgeExpired();

        org.mockito.Mockito.verify(lifecycle).clearContactExposure(tenantId, conversationId, expiredCapture);
    }

    @Test
    void markerFailurePreventsLiveDataDeletion() {
        var lifecycle = mock(AlternateContactLifecycleRepository.class);
        var staging = mock(AlternateContactStagingPort.class);
        var ledger = mock(AlternateContactDeletionLedger.class);
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        when(lifecycle.findExpiredTicketContacts(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new AlternateContactLifecycleRepository.TicketContactReference(tenant, ticketId, conversation,
                        Instant.EPOCH, null, Instant.EPOCH)));
        org.mockito.Mockito.doThrow(new IllegalStateException("external journal unavailable"))
                .when(ledger).record(any(DeletionMarker.class));
        var job = new AlternateContactRetentionJob(lifecycle, staging, ledger);

        assertThatThrownBy(job::purgeExpired).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(lifecycle, org.mockito.Mockito.never()).clearTicketContact(tenant, ticketId);
        org.mockito.Mockito.verify(staging, org.mockito.Mockito.never()).delete(any(), any(), any());
    }
}
