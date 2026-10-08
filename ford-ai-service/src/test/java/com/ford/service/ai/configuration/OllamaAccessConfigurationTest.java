package com.ford.service.ai.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaAccessConfigurationTest {

    @Test
    void addsAccessHeadersOnlyToOllamaApiRequests() {
        var properties = new OllamaAccessProperties();
        properties.setClientId("ollama-access-id");
        properties.setClientSecret("ollama-access-secret");
        var capturedRest = new AtomicReference<HttpHeaders>();
        var capturedWeb = new AtomicReference<HttpHeaders>();
        var restClient = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    capturedRest.set(request.getHeaders());
                    return execution.execute(request, body);
                });
        var webClient = WebClient.builder().filter((request, next) -> {
            capturedWeb.set(request.headers());
            return next.exchange(request);
        });
        var configured = OllamaAccessConfiguration.configureClients(restClient, webClient, properties,
                "https://ollama.emme.business");

        try {
            configured.restClientBuilder().build().get().uri("https://ollama.emme.business/api/tags")
                    .retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // The refused local connection occurs after the request interceptor runs.
        }
        try {
            configured.webClientBuilder().build().get().uri("https://ollama.emme.business/api/tags")
                    .retrieve().toBodilessEntity().block();
        } catch (Exception ignored) {
            // The refused local connection occurs after the request filter runs.
        }

        assertThat(capturedRest.get().getFirst("CF-Access-Client-Id")).isEqualTo("ollama-access-id");
        assertThat(capturedRest.get().getFirst("CF-Access-Client-Secret")).isEqualTo("ollama-access-secret");
        assertThat(capturedWeb.get().getFirst("CF-Access-Client-Id")).isEqualTo("ollama-access-id");
        assertThat(capturedWeb.get().getFirst("CF-Access-Client-Secret")).isEqualTo("ollama-access-secret");
    }

    @Test
    void leavesUnrelatedRestClientBuilderUnchanged() {
        var properties = new OllamaAccessProperties();
        properties.setClientId("ollama-access-id");
        properties.setClientSecret("ollama-access-secret");
        var captured = new AtomicReference<HttpHeaders>();
        var unrelatedClient = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    captured.set(request.getHeaders());
                    return execution.execute(request, body);
                });
        try {
            unrelatedClient.build().get().uri("http://127.0.0.1:1").retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // The refused local connection occurs after the request interceptor runs.
        }
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getFirst("CF-Access-Client-Id")).isNull();
    }
}
