package com.ford.service.inventoryapproval;

import com.ford.service.conversations.application.InventoryApprovalTriggerPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryApprovalTriggerPolicyTest {
    private final InventoryApprovalTriggerPolicy policy = new InventoryApprovalTriggerPolicy();

    @Test
    void identifiesDirectStockAndAvailabilityRequests() {
        assertThat(policy.isInventoryRequest("¿Tienen unidades de Ranger XLT?" )).isTrue();
        assertThat(policy.isInventoryRequest("¿Hay stock de la Bronco?" )).isTrue();
        assertThat(policy.isInventoryRequest("¿Está disponible la Explorer?" )).isTrue();
        assertThat(policy.isInventoryRequest("Quiero saber existencias de Maverick." )).isTrue();
    }

    @Test
    void ignoresNonInventoryProductAndPricingRequests() {
        assertThat(policy.isInventoryRequest("¿Qué motor tiene la Ranger?" )).isFalse();
        assertThat(policy.isInventoryRequest("¿Cuál es el precio de la Bronco?" )).isFalse();
        assertThat(policy.isInventoryRequest("" )).isFalse();
    }
}
