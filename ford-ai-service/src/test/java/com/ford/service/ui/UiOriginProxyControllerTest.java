package com.ford.service.ui;

import com.ford.service.ui.adapter.in.web.UiOriginProxyController;
import com.ford.service.ui.application.UiProxyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class UiOriginProxyControllerTest {

    @Test
    void proxiesUiPathAndQueryWithoutForwardingBrowserCookies() throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo("https://ford-crm-ui.pages.dev/leads/active?tab=hot"))
                .andExpect(header("Accept", "text/html"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Cookie")).isNull())
                .andRespond(withStatus(HttpStatus.OK)
                        .header("Content-Security-Policy", "default-src 'self'")
                        .header("Cache-Control", "public, max-age=14400")
                        .header("ETag", "W/\"html-v1\"")
                        .body("<main>CRM</main>"));
        var controller = new UiOriginProxyController(builder.build(), properties());
        var request = request("GET", "/leads/active", "tab=hot");
        request.addHeader("Accept", "text/html");
        request.addHeader("Cookie", "ford_session=secret-session-id");
        var response = new MockHttpServletResponse();

        controller.proxy(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("<main>CRM</main>");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'self'");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=0, must-revalidate");
        assertThat(response.getHeader("ETag")).isEqualTo("W/\"html-v1\"");
        upstream.verify();
    }

    @Test
    void fingerprintedAssetsUseImmutableCachingAndKeepConditionalValidation() throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo("https://ford-crm-ui.pages.dev/assets/index-AbCdEf09.js"))
                .andExpect(header("If-None-Match", "W/\"asset-v1\""))
                .andRespond(withStatus(HttpStatus.OK)
                        .header("Cache-Control", "public, max-age=14400")
                        .header("ETag", "W/\"asset-v1\"")
                        .body("export {};"));
        var controller = new UiOriginProxyController(builder.build(), properties());
        var request = request("GET", "/assets/index-AbCdEf09.js", null);
        request.addHeader("If-None-Match", "W/\"asset-v1\"");
        var response = new MockHttpServletResponse();

        controller.proxy(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("public, max-age=31536000, immutable");
        assertThat(response.getHeader("ETag")).isEqualTo("W/\"asset-v1\"");
        upstream.verify();
    }

    @Test
    void rewritesPagesOriginRedirectToPublicOrigin() throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo("https://ford-crm-ui.pages.dev/login?error"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header("Location", "https://ford-crm-ui.pages.dev/login?error"));
        var controller = new UiOriginProxyController(builder.build(), properties());
        var response = new MockHttpServletResponse();

        controller.proxy(request("GET", "/login", "error"), response);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("https://ford.emme.business/login?error");
        upstream.verify();
    }

    @Test
    void doesNotProxyBackendOrAuthenticationPaths() throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        var controller = new UiOriginProxyController(builder.build(), properties());

        for (var path : new String[]{"/api/v1/leads", "/api/auth/csrf", "/oauth2/authorize",
                "/.well-known/openid-configuration", "/webauthn/register/options", "/actuator/health",
                "/webhooks/meta/whatsapp", "/login/ott"}) {
            var response = new MockHttpServletResponse();
            controller.proxy(request("GET", path, null), response);
            assertThat(response.getStatus()).as(path).isEqualTo(404);
        }

        upstream.verify();
    }

    @Test
    void supportsHeadRequestsWithoutWritingResponseBody() throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo("https://ford-crm-ui.pages.dev/assets/app.js"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.HEAD))
                .andRespond(withStatus(HttpStatus.OK).header("Content-Type", "text/javascript"));
        var controller = new UiOriginProxyController(builder.build(), properties());
        var response = new MockHttpServletResponse();

        controller.proxy(request("HEAD", "/assets/app.js", null), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
        upstream.verify();
    }

    @Test
    void returnsBadGatewayWhenPagesIsUnavailable() throws Exception {
        var builder = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo("https://ford-crm-ui.pages.dev/"))
                .andRespond(request -> { throw new java.io.IOException("upstream unavailable"); });
        var controller = new UiOriginProxyController(builder.build(), properties());
        var response = new MockHttpServletResponse();

        controller.proxy(request("GET", "/", null), response);

        assertThat(response.getStatus()).isEqualTo(502);
        upstream.verify();
    }

    private static MockHttpServletRequest request(String method, String path, String query) {
        var request = new MockHttpServletRequest(method, path);
        if (query != null) request.setQueryString(query);
        return request;
    }

    private static UiProxyProperties properties() {
        return new UiProxyProperties(URI.create("https://ford-crm-ui.pages.dev"),
                URI.create("https://ford.emme.business"));
    }
}
