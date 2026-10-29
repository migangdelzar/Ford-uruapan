package com.ford.service.ai.application;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable content-free outcome for each authorized tool attempted in one generation. */
public record ToolExecutionSnapshot(List<ToolCall> calls) {
    public ToolExecutionSnapshot {
        calls = List.copyOf(calls == null ? List.of() : calls);
    }

    public enum Status { RETURNED, EMPTY, FAILED }

    public record ToolCall(String toolName, Status status, String reasonCode, Integer evidenceCount) {
        private static final Pattern TOOL = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,79}");
        private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");

        public ToolCall {
            if (toolName == null || !TOOL.matcher(toolName).matches())
                throw new IllegalArgumentException("tool name is invalid");
            Objects.requireNonNull(status, "tool status is required");
            if (reasonCode == null || !REASON.matcher(reasonCode).matches())
                throw new IllegalArgumentException("tool reason code is invalid");
            if (evidenceCount != null && evidenceCount < 0)
                throw new IllegalArgumentException("evidence count must be nonnegative");
        }
    }
}
