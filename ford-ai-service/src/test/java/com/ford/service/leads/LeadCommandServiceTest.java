package com.ford.service.leads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.leads.api.LeadUpdateRequest;
import com.ford.service.leads.application.LeadCommandService;
import com.ford.service.leads.application.LeadCommandService.LeadCommandRepository;
import com.ford.service.leads.application.LeadCommandService.LeadIdempotencyRepository;
import com.ford.service.leads.api.LeadView;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class LeadCommandServiceTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID LEAD = UUID.randomUUID();

    @Test
    void updatesLeadOnceAndReplaysSameIdempotencyKey() {
        var repository = mock(LeadCommandRepository.class);
        var idempotency = mock(LeadIdempotencyRepository.class);
        var before = new LeadView(LEAD, TENANT, UUID.randomUUID(), "Old", null, null, null, null,
                "warm", 50, Instant.now(), 3);
        var after = new LeadView(LEAD, TENANT, before.conversationId(), "New", null, null, null, null,
                "hot", 80, Instant.now(), 4);
        when(repository.find(TENANT, LEAD)).thenReturn(Optional.of(before));
        when(idempotency.find(any(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(idempotency.reserve(any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(repository.update(eq(TENANT), eq(LEAD), eq(3L), any())).thenReturn(after);
        var service = new LeadCommandService(repository, idempotency, new ObjectMapper().findAndRegisterModules());
        var operator = operator(TENANT);
        var request = new LeadUpdateRequest("New", null, null, null, null, "hot", "HUMAN");

        assertThat(service.update(operator, TENANT, LEAD, 3, "lead-key", request)).isEqualTo(after);
        verify(repository).update(eq(TENANT), eq(LEAD), eq(3L), eq(request));
        verify(idempotency).complete(eq(TENANT), eq(operator.subject()), anyString(), eq("lead-key"), anyString(), any());
    }

    @Test
    void rejectsStaleLeadVersionBeforeMutation() {
        var repository = mock(LeadCommandRepository.class);
        var idempotency = mock(LeadIdempotencyRepository.class);
        when(repository.find(TENANT, LEAD)).thenReturn(Optional.of(new LeadView(LEAD, TENANT, UUID.randomUUID(),
                "Name", null, null, null, null, "warm", 50, Instant.now(), 4)));
        when(idempotency.find(any(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(idempotency.reserve(any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        var service = new LeadCommandService(repository, idempotency, new ObjectMapper());

        assertThatThrownBy(() -> service.update(operator(TENANT), TENANT, LEAD, 3, "key",
                new LeadUpdateRequest(null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        verify(repository, never()).update(any(), any(), anyLong(), any());
    }

    private static AuthenticatedOperator operator(UUID tenant) {
        var jwt = Jwt.withTokenValue("token").subject("operator-1")
                .claim("tenant_id", tenant.toString()).header("alg", "none").build();
        return AuthenticatedOperator.from(new JwtAuthenticationToken(jwt, Set.of(() -> "SCOPE_leads:write")));
    }
}
