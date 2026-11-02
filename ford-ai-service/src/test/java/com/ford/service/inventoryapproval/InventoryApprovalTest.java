package com.ford.service.inventoryapproval;

import com.ford.service.inventoryapproval.domain.InventoryApproval;
import com.ford.service.inventoryapproval.domain.InventoryApprovalStatus;
import com.ford.service.inventoryapproval.domain.InventoryOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryApprovalTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void movesPendingToDraftAndApprovesExactTextOnce() {
        var advisorId = UUID.randomUUID();
        var approval = pending().verify(advisorId, InventoryOutcome.AVAILABLE, 2, NOW,
                NOW.plusSeconds(300), "El asesor confirmó 2 unidades de Ranger 2027 XLT.");

        assertThat(approval.status()).isEqualTo(InventoryApprovalStatus.DRAFT_READY);
        assertThat(approval.verifiedCount()).isEqualTo(2);

        var approved = approval.approve(advisorId, approval.draftText(), NOW.plusSeconds(20));

        assertThat(approved.status()).isEqualTo(InventoryApprovalStatus.APPROVED);
        assertThat(approved.approvedText()).isEqualTo(approval.draftText());
        assertThat(approved.approvedBy()).isEqualTo(advisorId);
        assertThat(approved.version()).isEqualTo(2);
        assertThat(approved.idempotencyKey()).contains(approval.id().toString());
    }

    @Test
    void rejectsApproveBeforeDraftAndDuplicateApproval() {
        var pending = pending();
        assertThatThrownBy(() -> pending.approve(UUID.randomUUID(), "Hay unidades", NOW))
                .isInstanceOf(IllegalStateException.class);

        var draft = pending.verify(UUID.randomUUID(), InventoryOutcome.UNAVAILABLE, null, NOW,
                NOW.plusSeconds(60), "El asesor confirmó que Ranger no tiene disponibilidad.");
        var approved = draft.approve(UUID.randomUUID(), draft.draftText(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> approved.approve(UUID.randomUUID(), "Otro texto", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesApprovalTextEditedAfterVerification() {
        var draft = pending().verify(UUID.randomUUID(), InventoryOutcome.AVAILABLE, 1, NOW,
                NOW.plusSeconds(60), "El asesor confirmó 1 unidad de Ranger.");

        assertThatThrownBy(() -> draft.approve(UUID.randomUUID(),
                "El asesor confirmó 9 unidades de Ranger.", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");
    }

    @Test
    void refusesExpiredVerificationAndInvalidCounts() {
        var draft = pending().verify(UUID.randomUUID(), InventoryOutcome.AVAILABLE, null, NOW,
                NOW.plusSeconds(2), "Disponibilidad confirmada.");
        assertThatThrownBy(() -> draft.approve(UUID.randomUUID(), "Disponibilidad confirmada.", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> pending().verify(UUID.randomUUID(), InventoryOutcome.AVAILABLE, -1, NOW,
                NOW.plusSeconds(60), "Texto"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().verify(UUID.randomUUID(), InventoryOutcome.UNAVAILABLE, 1, NOW,
                NOW.plusSeconds(60), "Texto"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionIsTerminalAndVersioned() {
        var rejected = pending().reject(UUID.randomUUID(), NOW);

        assertThat(rejected.status()).isEqualTo(InventoryApprovalStatus.REJECTED);
        assertThat(rejected.version()).isEqualTo(1);
        assertThatThrownBy(() -> rejected.reject(UUID.randomUUID(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static InventoryApproval pending() {
        return InventoryApproval.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Ranger", 2027, "XLT", "inventory-request:test", NOW);
    }
}
