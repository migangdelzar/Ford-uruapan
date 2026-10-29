package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class LocalQwenFailureCategoryTest {

    @Test
    void classifiesPersistedSafeReviewAndMissingEvidenceReasons() {
        assertThat(QwenTwoTurnWebhookReplayIntegrationTest.safeFailureCategory(
                new AssertionError("SAFE_FALLBACK_REVIEW")))
                .isEqualTo(LocalQwenTranscriptWriter.FailureCategory.SAFE_FALLBACK_REVIEW);
        assertThat(QwenTwoTurnWebhookReplayIntegrationTest.safeFailureCategory(
                new AssertionError("MISSING_CATALOG_EVIDENCE")))
                .isEqualTo(LocalQwenTranscriptWriter.FailureCategory.MISSING_CATALOG_EVIDENCE);
    }

    @Test
    void classifiesTimeoutAndDefaultsUnknownFailuresToInvalidState() {
        assertThat(QwenTwoTurnWebhookReplayIntegrationTest.safeFailureCategory(
                new HttpTimeoutException("synthetic timeout")))
                .isEqualTo(LocalQwenTranscriptWriter.FailureCategory.TURN_TIMEOUT);
        assertThat(QwenTwoTurnWebhookReplayIntegrationTest.safeFailureCategory(
                new IllegalStateException("synthetic unknown failure")))
                .isEqualTo(LocalQwenTranscriptWriter.FailureCategory.INVALID_STATE);
    }
}
