package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.MessageApprovalActionRequest;
import com.ford.service.conversations.application.MessageApprovalService;
import com.ford.service.conversations.application.port.out.IdempotencyRepository;
import com.ford.service.conversations.application.port.out.MessageApprovalEffects;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageApprovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T16:30:00Z");
    private final UUID tenant = UUID.randomUUID(), conversation = UUID.randomUUID(), receipt = UUID.randomUUID();
    private final MessageApprovalRepository repository = mock(MessageApprovalRepository.class);
    private final MessageApprovalEffects effects = mock(MessageApprovalEffects.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final MessageApprovalService service = new MessageApprovalService(repository, effects, idempotency,
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final AuthenticatedOperator operator = new AuthenticatedOperator(UUID.randomUUID().toString(),
            Set.of("SCOPE_operator:read", "SCOPE_operator:write", "SCOPE_handoff:manage"), Set.of(), Set.of(tenant));
    private final MessageApproval approval = MessageApproval.pending(UUID.randomUUID(), tenant, conversation, receipt, 7,
            new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.REVIEW,
                    AdvisorResponseDisposition.Reason.RESPONSE_REVIEW), "Texto exacto", true, NOW);

    @BeforeEach
    void setup() {
        when(repository.lockConversation(tenant, conversation)).thenReturn(
                Optional.of(new MessageApprovalRepository.ConversationTarget(7, "BOT_ACTIVE", receipt)));
        when(repository.findForUpdate(tenant, conversation, approval.id())).thenReturn(Optional.of(approval));
        when(repository.replace(any(), any())).thenReturn(true);
        when(idempotency.reserve(any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void queuesOnlyAfterValidatedEffectAndReplaysAnIdenticalCommandWithoutSendingAgain() {
        var stored = new AtomicReference<IdempotencyRepository.StoredCommand>();
        when(idempotency.find(any(), anyString(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(idempotency.reserve(any(), anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            stored.set(new IdempotencyRepository.StoredCommand(call.getArgument(4), null));
            return true;
        });
        doAnswer(call -> { stored.set(new IdempotencyRepository.StoredCommand(stored.get().requestHash(), call.getArgument(4))); return null; })
                .when(idempotency).complete(any(), anyString(), anyString(), anyString(), anyString(), any());
        when(effects.validateAndQueue(eq(operator), eq(approval), eq("Texto exacto"), anyString()))
                .thenReturn(new CommandReceipt(UUID.randomUUID(), conversation, true, "QUEUED", 8, false, NOW));
        var request = new MessageApprovalActionRequest(Action.APPROVE, 0, "Texto exacto");

        var first = service.act(operator, tenant, conversation, approval.id(), 7, "stable-key", request);
        var retry = service.act(operator, tenant, conversation, approval.id(), 7, "stable-key", request);

        assertThat(first.status()).isEqualTo("QUEUED");
        assertThat(retry).isEqualTo(first);
        verify(effects, times(1)).validateAndQueue(eq(operator), eq(approval), eq("Texto exacto"), anyString());
        verify(repository, times(1)).replace(eq(approval), argThat(next -> next.status() == MessageApproval.Status.QUEUED));
    }

    @Test
    void rejectsWrongTenantReadOnlyActorAndTakeoverWithoutHandoffPermissionBeforeEffects() {
        var readonly = new AuthenticatedOperator(operator.subject(), Set.of("SCOPE_operator:read"), Set.of(), Set.of(tenant));
        var writer = new AuthenticatedOperator(operator.subject(), Set.of("SCOPE_operator:write"), Set.of(), Set.of(tenant));
        var approve = new MessageApprovalActionRequest(Action.APPROVE, 0, "Texto exacto");
        assertThatThrownBy(() -> service.act(operator, UUID.randomUUID(), conversation, approval.id(), 7, "key", approve))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.act(readonly, tenant, conversation, approval.id(), 7, "key", approve))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.act(writer, tenant, conversation, approval.id(), 7, "key",
                new MessageApprovalActionRequest(Action.TAKEOVER, 0, null))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(effects);
    }

    @Test
    void staleConversationOrSourceReceiptCannotSend() {
        when(repository.lockConversation(tenant, conversation)).thenReturn(
                Optional.of(new MessageApprovalRepository.ConversationTarget(8, "BOT_ACTIVE", receipt)));
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "stale",
                new MessageApprovalActionRequest(Action.APPROVE, 0, "Texto exacto"))).isInstanceOf(IllegalStateException.class);
        when(repository.lockConversation(tenant, conversation)).thenReturn(
                Optional.of(new MessageApprovalRepository.ConversationTarget(7, "BOT_ACTIVE", UUID.randomUUID())));
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "wrong-receipt",
                new MessageApprovalActionRequest(Action.APPROVE, 0, "Texto exacto"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(effects);
    }

    @Test
    void blockedValidationDoesNotConsumeApprovalOrRecordSuccess() {
        when(effects.validateAndQueue(any(), any(), anyString(), anyString())).thenThrow(new IllegalStateException("Missing authority"));
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "blocked",
                new MessageApprovalActionRequest(Action.EDIT, 0, "Crédito aprobado"))).hasMessage("Missing authority");
        verify(repository, never()).replace(any(), any());
        verify(repository, never()).recordAudit(any(), any(), any(), any(), any());
        verify(idempotency, never()).complete(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectionDoesNotSendAndReadonlyListExposesNoMutationActions() {
        var rejected = service.act(operator, tenant, conversation, approval.id(), 7, "reject",
                new MessageApprovalActionRequest(Action.REJECT, 0, null));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        verifyNoInteractions(effects);
        verify(repository).replace(eq(approval), argThat(next -> next.blocksAutomatedReplies()));
        when(repository.list(tenant, conversation)).thenReturn(List.of(approval));
        var reader = new AuthenticatedOperator(operator.subject(), Set.of("SCOPE_operator:read"), Set.of(), Set.of(tenant));
        assertThat(service.list(reader, tenant, conversation).getFirst().allowedActions()).isEmpty();
    }

    @Test
    void cannotAcceptAnInternalOrUnacceptedMessageReceiptAsQueued() {
        when(effects.validateAndQueue(any(), any(), anyString(), anyString())).thenReturn(
                new CommandReceipt(UUID.randomUUID(), conversation, true, "INTERNAL", 8, true, NOW));
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "invalid-receipt",
                new MessageApprovalActionRequest(Action.APPROVE, 0, "Texto exacto"))).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).replace(any(), any());
    }

    @Test
    void takeoverUsesHandoffEffectAndNeverTheMessageSendEffect() {
        when(effects.takeover(eq(operator), eq(approval), anyString()))
                .thenReturn(new MessageApprovalEffects.TakeoverReceipt("HUMAN_ACTIVE", 9));
        var result = service.act(operator, tenant, conversation, approval.id(), 7, "takeover",
                new MessageApprovalActionRequest(Action.TAKEOVER, 0, null));
        assertThat(result.status()).isEqualTo("HUMAN_ACTIVE");
        assertThat(result.messageId()).isNull();
        verify(effects, never()).validateAndQueue(any(), any(), anyString(), anyString());
        verify(repository).replace(eq(approval), argThat(next -> next.status() == MessageApproval.Status.TAKEN_OVER));
    }

    @Test
    void approvalIdentityMustMatchEvenIfRepositoryReturnsAnotherDraftInSameConversation() {
        var other = MessageApproval.pending(UUID.randomUUID(), tenant, conversation, receipt, 7,
                approval.disposition(), "Texto exacto", true, NOW);
        when(repository.findForUpdate(tenant, conversation, approval.id())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "wrong-approval",
                new MessageApprovalActionRequest(Action.APPROVE, 0, "Texto exacto"))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(effects);
    }

    @Test
    void reusedKeyWithDifferentContentAndOverlongRequestsFailBeforeEffects() {
        when(idempotency.find(any(), anyString(), anyString(), anyString())).thenReturn(
                Optional.of(new IdempotencyRepository.StoredCommand("different-request-hash", "{}")));
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "used-key",
                new MessageApprovalActionRequest(Action.EDIT, 0, "Different text"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.act(operator, tenant, conversation, approval.id(), 7, "too-long",
                new MessageApprovalActionRequest(Action.EDIT, 0, "x".repeat(2001)))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(effects);
    }
}
