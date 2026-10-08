package com.ford.service.ai.configuration;

import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Objects;

/** Adds Cloudflare Access credentials only to requests targeting the configured Ollama tunnel host. */
public final class OllamaAccessConfiguration {

    private OllamaAccessConfiguration() {
    }

    public static ConfiguredClients configureClients(RestClient.Builder restClientBuilder,
                                                      WebClient.Builder webClientBuilder,
                                                      OllamaAccessProperties properties,
                                                      String baseUrl) {
        Objects.requireNonNull(restClientBuilder, "restClientBuilder is required");
        Objects.requireNonNull(webClientBuilder, "webClientBuilder is required");
        Objects.requireNonNull(properties, "properties is required");
        var targetHost = URI.create(Objects.requireNonNull(baseUrl, "baseUrl is required")).getHost();
        var rest = restClientBuilder.clone();
        var web = webClientBuilder.clone();
        var clientId = properties.clientId();
        var clientSecret = properties.clientSecret();
        if (targetHost != null && !targetHost.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()) {
            ClientHttpRequestInterceptor restInterceptor = (request, body, execution) -> {
                if (targetHost.equalsIgnoreCase(request.getURI().getHost())) {
                    request.getHeaders().set("CF-Access-Client-Id", clientId);
                    request.getHeaders().set("CF-Access-Client-Secret", clientSecret);
                }
                return execution.execute(request, body);
            };
            ExchangeFilterFunction webFilter = (request, next) -> {
                if (targetHost.equalsIgnoreCase(request.url().getHost())) {
                    var authorized = org.springframework.web.reactive.function.client.ClientRequest.from(request)
                            .headers(headers -> {
                                headers.set("CF-Access-Client-Id", clientId);
                                headers.set("CF-Access-Client-Secret", clientSecret);
                            }).build();
                    return next.exchange(authorized);
                }
                return next.exchange(request);
            };
            rest.requestInterceptor(restInterceptor);
            web.filters(filters -> filters.add(0, webFilter));
        }
        return new ConfiguredClients(rest, web);
    }

    public record ConfiguredClients(RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder) {
        public ConfiguredClients {
            Objects.requireNonNull(restClientBuilder);
            Objects.requireNonNull(webClientBuilder);
        }
    }
}
