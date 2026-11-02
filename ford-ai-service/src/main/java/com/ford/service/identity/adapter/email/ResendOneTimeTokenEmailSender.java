package com.ford.service.identity.adapter.email;

import com.ford.service.identity.application.OneTimeTokenEmailSender;
import com.ford.service.config.AuthenticationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class ResendOneTimeTokenEmailSender implements OneTimeTokenEmailSender {

    private final RestClient restClient;
    private final String apiKey;
    private final String from;

    public ResendOneTimeTokenEmailSender(RestClient.Builder builder, AuthenticationProperties properties) {
        this.restClient = builder.baseUrl("https://api.resend.com").build();
        this.apiKey = properties.emailApiKey();
        this.from = properties.emailFrom();
    }

    @Override
    public void send(String recipient, String magicLink) {
        if (apiKey.isBlank() || from.isBlank()) {
            throw new IllegalStateException("One-time-token email delivery is not configured");
        }
        restClient.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .body(Map.of(
                        "from", from,
                        "to", recipient,
                        "subject", "Acceso seguro a Ford Uruapan",
                        "text", "Usa este enlace de un solo uso para iniciar sesión. Caduca en 5 minutos: " + magicLink))
                .retrieve()
                .toBodilessEntity();
    }
}
