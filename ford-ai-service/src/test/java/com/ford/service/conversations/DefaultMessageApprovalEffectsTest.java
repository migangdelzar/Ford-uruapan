package com.ford.service.conversations;

import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.conversations.application.DefaultMessageApprovalEffects;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.application.port.out.MessageApprovalSendValidationPort;
import com.ford.service.conversations.application.ConversationCommandService;
import com.ford.service.conversations.api.MessageApprovalTakeoverPort;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultMessageApprovalEffectsTest {
    private static final Instant NOW = Instant.parse("2026-10-28T16:30:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();
    private final UUID receipt = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final MessageApproval approval = MessageApproval.pending(UUID.randomUUID(), tenant, conversation, receipt, 8,
            new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.REVIEW,
                    AdvisorResponseDisposition.Reason.RESPONSE_REVIEW), "Draft", true, NOW);
    private final AuthenticatedOperator operator = new AuthenticatedOperator(actor.toString(),
            Set.of("SCOPE_operator:write", "SCOPE_handoff:manage"), Set.of(), Set.of(tenant));
    private final ConversationCommandService commands = mock(ConversationCommandService.class);
    private final MessageApprovalSendValidationPort validation = mock(MessageApprovalSendValidationPort.class);
    private final MessageApprovalTakeoverPort handoffs = mock(MessageApprovalTakeoverPort.class);
    private final MessageApprovalRepository repository = mock(MessageApprovalRepository.class);
    private final DefaultMessageApprovalEffects effects = new DefaultMessageApprovalEffects(
            commands, validation, handoffs, repository);

    @Test
    void sendsExactConfirmedTextThroughExistingCommandAndValidatesBeforeQueueing() {
        var receipt = new CommandReceipt(UUID.randomUUID(), conversation, true, "QUEUED", 9, false, NOW);
        when(commands.sendMessage(eq(operator), eq(tenant), eq(conversation), eq(8L), eq("stable-command"), any()))
                .thenReturn(receipt);

        var actual = effects.validateAndQueue(operator, approval, "Draft", "stable-command");

        assertThat(actual).isEqualTo(receipt);
        verify(validation).validate(approval, MessageApproval.Action.APPROVE, "Draft");
        verify(commands).sendMessage(eq(operator), eq(tenant), eq(conversation), eq(8L), eq("stable-command"),
                argThat(request -> "Draft".equals(request.content()) && !request.internalNote()));
    }

    @Test
    void replacementUsesEditValidationAndCannotSendBeforeItPasses() {
        doThrow(new IllegalStateException("edit not grounded")).when(validation)
                .validate(approval, MessageApproval.Action.EDIT, "edited");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                effects.validateAndQueue(operator, approval, "edited", "stable-command"))
                .hasMessage("edit not grounded");
        verifyNoInteractions(commands);
    }

    @Test
    void takeoverReusesExistingHandoffRequestAndClaimPath() {
        when(handoffs.takeover(operator, tenant, conversation))
                .thenReturn(new MessageApprovalTakeoverPort.TakeoverOutcome("HUMAN_ACTIVE"));
        when(repository.lockConversation(tenant, conversation)).thenReturn(java.util.Optional.of(
                new MessageApprovalRepository.ConversationTarget(10, "HUMAN_ACTIVE", receipt)));

        var result = effects.takeover(operator, approval, "stable-command");

        assertThat(result.status()).isEqualTo("HUMAN_ACTIVE");
        assertThat(result.conversationVersion()).isEqualTo(10);
        verify(handoffs).takeover(operator, tenant, conversation);
        verifyNoInteractions(commands, validation);
    }
}
