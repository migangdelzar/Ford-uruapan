package com.ford.service.ai.application;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Content-free evidence status and references for one assistant response. */
public record EvidenceResult(Status status, List<String> sourceReferences, int evidenceCount) {
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,159}");

    public EvidenceResult {
        Objects.requireNonNull(status, "evidence status is required");
        sourceReferences = List.copyOf(sourceReferences == null ? List.of() : sourceReferences);
        if (evidenceCount < 0) throw new IllegalArgumentException("evidence count must be nonnegative");
        if (sourceReferences.stream().anyMatch(ref -> ref == null || !REFERENCE.matcher(ref).matches())) {
            throw new IllegalArgumentException("evidence source reference is invalid");
        }
        switch (status) {
            case SUPPORTED -> {
                if (evidenceCount == 0 || sourceReferences.isEmpty())
                    throw new IllegalArgumentException("supported evidence requires a positive count and source reference");
            }
            case UNKNOWN -> {
                if (evidenceCount != 0 || !sourceReferences.isEmpty())
                    throw new IllegalArgumentException("unknown evidence cannot include sources or positive count");
            }
            case CONFLICTED -> {
                if (evidenceCount < 2 || sourceReferences.size() < 2)
                    throw new IllegalArgumentException("conflicted evidence requires multiple source references");
            }
        }
    }

    public static EvidenceResult supported(List<String> sourceReferences, int evidenceCount) {
        return new EvidenceResult(Status.SUPPORTED, sourceReferences, evidenceCount);
    }

    public static EvidenceResult unknown() {
        return new EvidenceResult(Status.UNKNOWN, List.of(), 0);
    }

    public static EvidenceResult conflicted(List<String> sourceReferences, int evidenceCount) {
        return new EvidenceResult(Status.CONFLICTED, sourceReferences, evidenceCount);
    }

    public static EvidenceResult from(ToolExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "tool execution snapshot is required");
        var supported = snapshot.calls().stream()
                .filter(call -> call.status() == ToolExecutionSnapshot.Status.RETURNED
                        && call.evidenceCount() != null && call.evidenceCount() > 0)
                .toList();
        if (supported.isEmpty()) return unknown();
        var count = supported.stream().mapToInt(ToolExecutionSnapshot.ToolCall::evidenceCount).sum();
        var references = supported.stream().map(call -> "tool:" + call.toolName()).distinct().toList();
        return supported(references, count);
    }

    public enum Status { SUPPORTED, UNKNOWN, CONFLICTED }
}
