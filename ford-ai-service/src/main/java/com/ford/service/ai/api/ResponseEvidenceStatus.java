package com.ford.service.ai.api;

/** Reports whether a required read-only source returned data for this response. */
public enum ResponseEvidenceStatus {
    NOT_REQUIRED,
    READ_ONLY_TOOL_RETURNED,
    READ_ONLY_TOOL_RETURNED_EMPTY,
    READ_ONLY_TOOL_FAILED,
    REQUIRED_TOOL_NOT_CALLED
}
