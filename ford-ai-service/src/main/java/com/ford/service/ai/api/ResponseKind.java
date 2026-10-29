package com.ford.service.ai.api;

/** Final response purpose, separate from tool/evidence status. */
public enum ResponseKind {
    ANSWER,
    CLARIFICATION,
    SAFE_FALLBACK
}
