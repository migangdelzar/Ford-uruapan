package com.ford.service.conversations;

import com.ford.service.conversations.application.ShadowDraftService;
import com.ford.service.conversations.application.port.out.ShadowDraftRepository;
import com.ford.service.conversations.domain.ShadowDraft;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShadowDraftServiceTest {
    @Test
    void savesBoundedUnsentDraftWithExpiry() {
        var drafts = mock(ShadowDraftRepository.class);
        var service = new ShadowDraftService(drafts);
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var start = Instant.parse("2026-10-17T12:00:00Z");
        var text = "a".repeat(5000);

        service.record(tenant, conversation, receipt, "wamid.1", text, start);

        var captor = org.mockito.ArgumentCaptor.forClass(ShadowDraft.class);
        verify(drafts).save(captor.capture());
        assertThat(captor.getValue().text()).hasSize(2000);
        assertThat(captor.getValue().expiresAt()).isEqualTo(start.plusSeconds(7 * 86400));
        assertThat(captor.getValue().tenantId()).isEqualTo(tenant);
        assertThat(captor.getValue().receiptId()).isEqualTo(receipt);
    }

    @Test
    void tenantAndWriteAuthorityAreRequiredForReview() {
        var drafts = mock(ShadowDraftRepository.class);
        var service = new ShadowDraftService(drafts);
        var tenant = UUID.randomUUID();
        var wrongTenant = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var draftId = UUID.randomUUID();
        var reader = new AuthenticatedOperator("reader", Set.of("SCOPE_operator:read"), Set.of(), Set.of(tenant));
        var writer = new AuthenticatedOperator("writer", Set.of("SCOPE_operator:write"), Set.of(), Set.of(wrongTenant));

        assertThatThrownBy(() -> service.review(reader, tenant, conversationId, draftId, "ACCEPTED", "ok"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.review(writer, tenant, conversationId, draftId, "REJECTED", "unsafe"))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(drafts);
    }

    @Test
    void reviewRequiresValidDecisionAndBoundedNote() {
        var drafts = mock(ShadowDraftRepository.class);
        var service = new ShadowDraftService(drafts);
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var operator = new AuthenticatedOperator("writer", Set.of("SCOPE_operator:write"), Set.of(), Set.of(tenant));

        assertThatThrownBy(() -> service.review(operator, tenant, conversation, UUID.randomUUID(), "SEND", "ok"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.review(operator, tenant, conversation, UUID.randomUUID(), "ACCEPTED", "x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(drafts);
    }

    @Test
    void authorizedReadUsesTenantScopedRepository() {
        var drafts = mock(ShadowDraftRepository.class);
        var service = new ShadowDraftService(drafts);
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var operator = new AuthenticatedOperator("reader", Set.of("SCOPE_operator:read"), Set.of(), Set.of(tenant));
        when(drafts.list(tenant, conversation, 50)).thenReturn(List.of());

        assertThat(service.list(operator, tenant, conversation)).isEmpty();
        verify(drafts).list(tenant, conversation, 50);
        verify(drafts, never()).save(any());
    }
}
