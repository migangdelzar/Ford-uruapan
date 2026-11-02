package com.ford.service.leads;

import com.ford.service.leads.application.LeadJourneyPolicy;
import com.ford.service.leads.application.LeadJourneyRepository;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.leads.domain.LeadJourneyTopic;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeadJourneyServiceTest {

    @Test
    void raisesOptimisticWriteFailureSoTheConversationTransactionRollsBack() {
        var repository = mock(LeadJourneyRepository.class);
        var policy = mock(LeadJourneyPolicy.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var snapshot = new LeadJourneySnapshot(tenantId, conversationId, null, null, Set.of(), Set.of(),
                Map.of(), null, 0, null, 2, Instant.now());
        var updated = new LeadJourneySnapshot(tenantId, conversationId, null,
                LeadJourneyTopic.VEHICLE_DISCOVERY, Set.of(LeadJourneyTopic.VEHICLE_DISCOVERY), Set.of(),
                Map.of(), null, 0, "wamid.turn-1", 2, Instant.now());
        when(policy.apply(eq(snapshot), any(LeadJourneyProposal.class), eq("Ranger"), anyBoolean(),
                eq("wamid.turn-1"), any(Instant.class))).thenReturn(updated);
        when(repository.update(tenantId, conversationId, 2, updated, "wamid.turn-1")).thenReturn(false);
        var service = new LeadJourneyService(repository, policy);

        assertThatThrownBy(() -> service.apply(tenantId, conversationId, snapshot,
                new LeadJourneyProposal(LeadJourneyTopic.VEHICLE_DISCOVERY, java.util.List.of(), null),
                "Ranger", false, "wamid.turn-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version race");
    }
}
