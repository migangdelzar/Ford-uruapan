package com.ford.service.handoff;

import com.ford.service.handoff.application.DefaultMessageApprovalTakeoverAdapter;
import com.ford.service.handoff.application.HandoffService;
import com.ford.service.handoff.api.OperatorPrincipal;
import com.ford.service.handoff.domain.HandoffPriority;
import com.ford.service.handoff.domain.HandoffRequest;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoff.domain.HandoffTicket;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMessageApprovalTakeoverAdapterTest {
    @Test
    void requestsThenClaimsHandoffUsingAuthenticatedTenantAndOperator() {
        var service = mock(HandoffService.class);
        var adapter = new DefaultMessageApprovalTakeoverAdapter(service);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var operatorId = UUID.randomUUID();
        var operator = new AuthenticatedOperator(operatorId.toString(), Set.of("SCOPE_handoff:manage"),
                Set.of("advisor"), Set.of(tenantId));
        var now = Instant.parse("2026-10-28T16:30:00Z");
        var pending = new HandoffTicket(UUID.randomUUID(), tenantId, conversationId, null,
                HandoffStatus.HUMAN_PENDING, HandoffPriority.NORMAL, null, "advisor_review", "key", 1,
                now, null, null);
        var active = new HandoffTicket(pending.id(), tenantId, conversationId, null,
                HandoffStatus.HUMAN_ACTIVE, HandoffPriority.NORMAL, operatorId, "advisor_review", "key", 2,
                now, now, null);
        when(service.request(any(HandoffRequest.class), any(OperatorPrincipal.class))).thenReturn(pending);
        when(service.claim(eq(pending.id()), eq(pending.version()), any(OperatorPrincipal.class))).thenReturn(active);

        var result = adapter.takeover(operator, tenantId, conversationId);

        assertThat(result.status()).isEqualTo("HUMAN_ACTIVE");
        var request = ArgumentCaptor.forClass(HandoffRequest.class);
        var principal = ArgumentCaptor.forClass(OperatorPrincipal.class);
        verify(service).request(request.capture(), principal.capture());
        assertThat(request.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(request.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(request.getValue().reason()).isEqualTo("advisor_message_approval_takeover");
        assertThat(principal.getValue().operatorId()).isEqualTo(operatorId);
        assertThat(principal.getValue().tenantIds()).containsExactly(tenantId);
        assertThat(principal.getValue().authorities()).contains("SCOPE_handoff:manage", "ROLE_advisor");
        verify(service).claim(pending.id(), pending.version(), principal.getValue());
    }
}
