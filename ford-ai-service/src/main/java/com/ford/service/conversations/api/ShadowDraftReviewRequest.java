package com.ford.service.conversations.api;

/** Operator review decision for a shadow draft. There is no send action. */
public record ShadowDraftReviewRequest(String decision, String note) {
}
