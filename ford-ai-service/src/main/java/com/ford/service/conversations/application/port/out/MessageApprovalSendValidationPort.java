package com.ford.service.conversations.application.port.out;

import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.MessageApproval.Action;

public interface MessageApprovalSendValidationPort {
    void validate(MessageApproval approval, Action action, String exactText);
}
