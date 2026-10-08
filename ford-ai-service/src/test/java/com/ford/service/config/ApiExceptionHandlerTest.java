package com.ford.service.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    @Test
    void returnsActionableMethodNotAllowedDetails() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/meta/whatsapp");
        var exception = new HttpRequestMethodNotSupportedException("POST", Set.of("GET"));

        var response = new ApiExceptionHandler().handleMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("HTTP method not supported");
        assertThat(response.getBody().getDetail())
                .contains("POST", "/webhooks/meta/whatsapp", "GET");
        assertThat(response.getBody().getProperties())
                .containsEntry("path", "/webhooks/meta/whatsapp")
                .containsEntry("method", "POST");
    }

    @Test
    void logsTheCauseOfAStateConflictWithoutLoggingRequestContent(CapturedOutput output) {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/meta/whatsapp");

        new ApiExceptionHandler().handleConflict(
                new IllegalStateException("webhook state is unavailable"), request);

        assertThat(output.getOut())
                .contains("HTTP state conflict")
                .contains("path=/webhooks/meta/whatsapp")
                .contains("reason=webhook state is unavailable");
    }
}
