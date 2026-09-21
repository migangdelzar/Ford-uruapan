package com.ford.service.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class HttpExchangeLoggingFilterTest {

    @Test
    void logsRequestArrivalAndCompletionWithoutLoggingTheBody(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/webhooks/meta/whatsapp");
        request.setQueryString("hub.verify_token=do-not-log");
        request.setContentType("application/json");
        request.setContent("phone-number-and-message-body".getBytes());
        var response = new MockHttpServletResponse();

        new HttpExchangeLoggingFilter().doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(202));

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(output.getOut())
                .contains("HTTP request started")
                .contains("HTTP request completed")
                .contains("method=POST")
                .contains("path=/webhooks/meta/whatsapp")
                .contains("status=202")
                .contains("queryPresent=true")
                .doesNotContain("phone-number-and-message-body")
                .doesNotContain("do-not-log");
    }
}
