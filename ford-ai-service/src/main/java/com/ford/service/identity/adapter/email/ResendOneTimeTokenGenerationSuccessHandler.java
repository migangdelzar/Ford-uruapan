package com.ford.service.identity.adapter.email;

import com.ford.service.config.AuthenticationProperties;
import com.ford.service.identity.application.OneTimeTokenEmailSender;
import com.ford.service.identity.application.OneTimeTokenResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class ResendOneTimeTokenGenerationSuccessHandler implements OneTimeTokenGenerationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(ResendOneTimeTokenGenerationSuccessHandler.class);
    private final OneTimeTokenEmailSender emailSender;
    private final AuthenticationProperties properties;

    public ResendOneTimeTokenGenerationSuccessHandler(
            OneTimeTokenEmailSender emailSender, AuthenticationProperties properties) {
        this.emailSender = emailSender;
        this.properties = properties;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, OneTimeToken token)
            throws IOException, ServletException {
        if (token instanceof OneTimeTokenResult result && result.deliveryAllowed()) {
            try {
                var link = UriComponentsBuilder.fromUriString(properties.publicBaseUrl())
                        .path("/login/ott")
                        .queryParam("token", token.getTokenValue())
                        .build().encode().toUriString();
                emailSender.send(token.getUsername(), link);
            } catch (RuntimeException exception) {
                log.warn("One-time-token email delivery failed; account response remains neutral");
            }
        }
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setContentType("application/json");
        response.getWriter().write("{\"accepted\":true}");
    }
}
