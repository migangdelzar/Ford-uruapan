package com.ford.service.identity.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorAuthorizationEntryPointTest {

    @Test
    void sendsUnauthenticatedAuthorizationRequestsToTheOperatorLoginPage() throws Exception {
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        var response = new MockHttpServletResponse();

        new OperatorAuthenticationConfiguration().authorizationServerLoginEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("operator login required"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }
}
