package com.ford.service.conversations.application;

import com.ford.service.conversations.api.CommandReceipt;
import com.ford.service.conversations.api.MessageApprovalTakeoverPort;
import com.ford.service.conversations.api.SendMessageRequest;
import com.ford.service.conversations.application.port.out.MessageApprovalEffects;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.application.port.out.MessageApprovalSendValidationPort;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.operator.application.AuthenticatedOperator;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Uses existing transactional operator-message/outbox and handoff/claim workflows. */
@Component
public final class DefaultMessageApprovalEffects implements MessageApprovalEffects {
    private final ConversationCommandService commands;
    private final MessageApprovalSendValidationPort validation;
    private final MessageApprovalTakeoverPort handoffs;
    private final MessageApprovalRepository approvals;

    public DefaultMessageApprovalEffects(ConversationCommandService commands,
                                         MessageApprovalSendValidationPort validation,
                                         MessageApprovalTakeoverPort handoffs,
                                         MessageApprovalRepository approvals) {
        this.commands = Objects.requireNonNull(commands);
        this.validation = Objects.requireNonNull(validation);
        this.handoffs = Objects.requireNonNull(handoffs);
        this.approvals = Objects.requireNonNull(approvals);
    }

    @Override
    public CommandReceipt validateAndQueue(AuthenticatedOperator operator, MessageApproval approval,
                                           String exactText, String commandKey) {
        Objects.requireNonNull(operator);
        Objects.requireNonNull(approval);
        var action = exactText != null && exactText.equals(approval.draftText())
                ? MessageApproval.Action.APPROVE : MessageApproval.Action.EDIT;
        validation.validate(approval, action, exactText);
        return commands.sendMessage(operator, approval.tenantId(), approval.conversationId(),
                approval.conversationVersion(), commandKey,
                new SendMessageRequest(exactText, commandKey, false));
    }

    @Override
    public TakeoverReceipt takeover(AuthenticatedOperator operator, MessageApproval approval, String commandKey) {
        Objects.requireNonNull(operator);
        Objects.requireNonNull(approval);
        var ticket = handoffs.takeover(operator, approval.tenantId(), approval.conversationId());
        if (!Set.of("HUMAN_ACTIVE", "HUMAN_PENDING").contains(ticket.status())) {
            throw new IllegalStateException("handoff could not take control of the conversation");
        }
        var conversation = approvals.lockConversation(approval.tenantId(), approval.conversationId())
                .orElseThrow(() -> new IllegalStateException("conversation disappeared during takeover"));
        if (!Set.of("HUMAN_PENDING", "HUMAN_ACTIVE").contains(conversation.state())) {
            throw new IllegalStateException("conversation control was not transferred");
        }
        return new TakeoverReceipt(conversation.state(), conversation.version());
    }
}
