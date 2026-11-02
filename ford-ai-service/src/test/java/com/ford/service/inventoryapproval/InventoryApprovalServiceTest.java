package com.ford.service.inventoryapproval;

import com.ford.service.inventoryapproval.application.InventoryApprovalService;
import com.ford.service.inventoryapproval.api.InventoryApprovalMessagePort;
import com.ford.service.inventoryapproval.application.port.out.InventoryApprovalRepository;
import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.inventoryapproval.domain.InventoryOutcome;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryApprovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final UUID operatorId = UUID.randomUUID();
    private final InventoryApprovalRepository repository = mock(InventoryApprovalRepository.class);
    private final InventoryApprovalMessagePort approvedMessages = mock(InventoryApprovalMessagePort.class);
    private final InventoryApprovalService service = new InventoryApprovalService(repository, approvedMessages,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final AuthenticatedOperator operator = new AuthenticatedOperator(operatorId.toString(),
            Set.of("SCOPE_operator:write", "SCOPE_operator:read"), Set.of("OPERATOR"), Set.of(tenantId));

    @Test
    void createsPendingRequestWithoutCustomerPromptAndIsIdempotent() {
        var saved = new java.util.concurrent.atomic.AtomicReference<InventoryApproval>();
        when(repository.savePending(any())).thenAnswer(invocation -> {
            if (saved.get() == null) saved.set(invocation.getArgument(0));
            return saved.get();
        });

        var first = service.request(tenantId, conversationId, "Ranger", 2027, "XLT", "inbound:abc");
        var retry = service.request(tenantId, conversationId, "Ranger", 2027, "XLT", "inbound:abc");

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo(InventoryApprovalStatus.PENDING);
        verify(repository, times(2)).savePending(any());
        verify(repository).recordAudit(eq(tenantId), eq(first.id()), eq(new UUID(0, 0)),
                eq("REQUESTED"), eq(NOW), eq(0L));
    }

    @Test
    void verifiesAndApprovesExactTextThroughApprovedMessagePort() {
        var pending = pending();
        when(repository.find(tenantId, approvalId)).thenReturn(Optional.of(pending));
        when(repository.transition(eq(tenantId), eq(approvalId), eq(0L), eq(InventoryApprovalStatus.PENDING), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(4)));
        var verified = service.verify(operator, tenantId, approvalId, 0,
                InventoryOutcome.AVAILABLE, 2);
        var draft = verified.draftText();
        when(repository.find(tenantId, approvalId)).thenReturn(Optional.of(verified));
        when(repository.transition(eq(tenantId), eq(approvalId), eq(1L), eq(InventoryApprovalStatus.DRAFT_READY), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(4)));
        when(approvedMessages.sendApprovedMessage(eq(operator), eq(tenantId), eq(conversationId), eq(8L), any(), any()))
                .thenReturn(true);

        var approved = service.approve(operator, tenantId, approvalId, 1, 8, draft);

        assertThat(verified.status()).isEqualTo(InventoryApprovalStatus.DRAFT_READY);
        assertThat(draft).contains("2 unidades");
        assertThat(approved.status()).isEqualTo(InventoryApprovalStatus.APPROVED);
        assertThat(approved.approvedText()).isEqualTo(draft);
        verify(approvedMessages).sendApprovedMessage(eq(operator), eq(tenantId), eq(conversationId), eq(8L),
                eq("inventory-approval:" + approvalId + ":1"), eq(draft));
        verify(repository).recordAudit(tenantId, approvalId, operatorId, "APPROVED", NOW, 2);
    }

    @Test
    void refusesWrongTenantStaleVersionAndInvalidCounts() {
        var pending = pending();
        when(repository.find(tenantId, approvalId)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.verify(operator, UUID.randomUUID(), approvalId, 0,
                InventoryOutcome.AVAILABLE, null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.verify(operator, tenantId, approvalId, 4,
                InventoryOutcome.AVAILABLE, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.verify(operator, tenantId, approvalId, 0,
                InventoryOutcome.UNAVAILABLE, 1)).isInstanceOf(IllegalArgumentException.class);
        verify(approvedMessages, never()).sendApprovedMessage(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void cannotApprovePendingTaskOrQueueMessage() {
        when(repository.find(tenantId, approvalId)).thenReturn(Optional.of(pending()));

        assertThatThrownBy(() -> service.approve(operator, tenantId, approvalId, 0, 8, "Unverified text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
        verify(approvedMessages, never()).sendApprovedMessage(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void expiredApprovalMarksTaskExpiredAndDoesNotSend() {
        var draft = pending().verify(operatorId, InventoryOutcome.AVAILABLE, null, NOW.minusSeconds(600),
                NOW.minusSeconds(1), "Disponibilidad confirmada.");
        when(repository.find(tenantId, approvalId)).thenReturn(Optional.of(draft));
        when(repository.transition(eq(tenantId), eq(approvalId), eq(1L), eq(InventoryApprovalStatus.DRAFT_READY), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(4)));

        var result = service.approve(operator, tenantId, approvalId, 1, 8, "Texto aprobado");

        assertThat(result.status()).isEqualTo(InventoryApprovalStatus.EXPIRED);
        verify(approvedMessages, never()).sendApprovedMessage(any(), any(), any(), anyLong(), any(), any());
    }

    private InventoryApproval pending() {
        return InventoryApproval.pending(approvalId, tenantId, conversationId,
                "Ranger", 2027, "XLT", "request-key", NOW);
    }
}
