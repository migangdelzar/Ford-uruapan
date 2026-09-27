package com.ford.service.ai;

import com.ford.service.ai.application.AiProviderFailureDiagnostics;
import com.ford.service.ai.application.ProviderFailure;
import com.fasterxml.jackson.core.JsonParseException;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderFailureTest {

    @Test
    void classifiesRateLimitAndParsesNumericRetryAfterSeconds() {
        var failure = openAiFailure(429, "12");

        assertThat(AiProviderFailureDiagnostics.classify(failure))
                .isEqualTo(new ProviderFailure(ProviderFailure.RATE_LIMITED, 429, Duration.ofSeconds(12)));
    }

    @Test
    void leavesRetryAfterEmptyWhenTheHeaderIsInvalidOrMissing() {
        assertThat(AiProviderFailureDiagnostics.classify(openAiFailure(429, "12 seconds")).retryAfter())
                .isNull();
        assertThat(AiProviderFailureDiagnostics.classify(openAiFailure(429, null)).retryAfter())
                .isNull();
        assertThat(AiProviderFailureDiagnostics.classify(openAiFailure(429, "-1")).retryAfter())
                .isNull();
    }

    @Test
    void classifiesAuthenticationAndUnavailableResponses() {
        assertThat(AiProviderFailureDiagnostics.classify(openAiFailure(401, null)).category())
                .isEqualTo(ProviderFailure.AUTHENTICATION);
        assertThat(AiProviderFailureDiagnostics.classify(openAiFailure(503, null)).category())
                .isEqualTo(ProviderFailure.PROVIDER_UNAVAILABLE);
    }

    @Test
    void classifiesTimeoutAndUnknownFailuresWithoutRetainingTheirMessages() {
        var timeout = new SocketTimeoutException("prompt=customer text; token=secret");
        var unknown = new IllegalStateException("responseBody=private customer content");

        assertThat(AiProviderFailureDiagnostics.classify(timeout).category()).isEqualTo(ProviderFailure.NETWORK);
        assertThat(AiProviderFailureDiagnostics.classify(unknown).category()).isEqualTo(ProviderFailure.PROVIDER_ERROR);
        assertThat(AiProviderFailureDiagnostics.classify(timeout).toString())
                .doesNotContain("customer text", "secret", "private customer content", "responseBody");
    }

    @Test
    void doesNotMisclassifyStreamParsingFailuresAsNetworkFailures() {
        var parseFailure = new JsonParseException("invalid provider response stream");

        assertThat(AiProviderFailureDiagnostics.classify(parseFailure).category())
                .isEqualTo(ProviderFailure.PROVIDER_ERROR);
        assertThat(AiProviderFailureDiagnostics.describe(parseFailure, false))
                .contains("category=provider_error");
    }

    private static OpenAIServiceException openAiFailure(int status, String retryAfter) {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(status);
        var headers = Headers.builder();
        if (retryAfter != null) {
            headers.put("retry-after", retryAfter);
        }
        when(failure.headers()).thenReturn(headers.build());
        return failure;
    }
}
