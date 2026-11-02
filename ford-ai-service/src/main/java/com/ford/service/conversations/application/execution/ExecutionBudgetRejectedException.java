package com.ford.service.conversations.application.execution;

/** Safe, data-free rejection reason for a budget operation. */
public final class ExecutionBudgetRejectedException extends RuntimeException {
    public enum Reason { DEADLINE_EXCEEDED, LIMIT_EXCEEDED, STALE_FENCE, POLICY_MISMATCH, OPERATION_ID_CONFLICT, INVALID_SNAPSHOT }

    private final Reason reason;

    public ExecutionBudgetRejectedException(Reason reason) {
        super("Turn execution budget rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
