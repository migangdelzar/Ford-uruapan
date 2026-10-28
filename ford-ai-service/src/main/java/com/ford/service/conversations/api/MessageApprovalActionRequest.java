package com.ford.service.conversations.api;

import com.ford.service.conversations.domain.MessageApproval.Action;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record MessageApprovalActionRequest(@NotNull Action action, @PositiveOrZero long expectedApprovalVersion,
                                          @Size(max = 2000) String exactText) {
    @Override public String toString() {
        return "MessageApprovalActionRequest[action=" + action + ", version=" + expectedApprovalVersion + "]";
    }
}
