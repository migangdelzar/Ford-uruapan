package com.ford.service.ai.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "ford.ai.providers")
public class ChatProviderProperties {

    private static final Set<String> ALLOWED_FALLBACK_PROVIDERS = Set.of("groq", "gemini", "cloudflare", "openrouter");

    private List<String> fallbackOrder = new ArrayList<>(List.of("cloudflare", "groq", "gemini", "openrouter"));

    @Valid
    private Map<String, Provider> catalog = new LinkedHashMap<>();

    private boolean evaluationMode;

    public List<String> fallbackOrder() {
        return List.copyOf(fallbackOrder);
    }

    public void setFallbackOrder(List<String> fallbackOrder) {
        this.fallbackOrder = fallbackOrder == null ? new ArrayList<>() : new ArrayList<>(fallbackOrder);
    }

    public Map<String, Provider> catalog() {
        return Collections.unmodifiableMap(catalog);
    }

    public void setCatalog(Map<String, Provider> catalog) {
        this.catalog = catalog == null ? new LinkedHashMap<>() : new LinkedHashMap<>(catalog);
    }

    public boolean evaluationMode() {
        return evaluationMode;
    }

    public void setEvaluationMode(boolean evaluationMode) {
        this.evaluationMode = evaluationMode;
    }

    public List<String> eligibleFallbackIds() {
        return fallbackOrder.stream()
                .filter(ALLOWED_FALLBACK_PROVIDERS::contains)
                .filter(id -> {
                    var provider = catalog.get(id);
                    return provider != null && provider.ready()
                            && (!provider.requiresEvaluationMode() || evaluationMode);
                })
                .toList();
    }

    /** Provider IDs and readiness labels only; credential values are never returned. */
    public Map<String, String> providerStatus() {
        var status = new LinkedHashMap<String, String>();
        for (var id : fallbackOrder) {
            if (!ALLOWED_FALLBACK_PROVIDERS.contains(id)) {
                continue;
            }
            var provider = catalog.get(id);
            status.put(id, status(provider));
        }
        return Collections.unmodifiableMap(status);
    }

    private String status(Provider provider) {
        if (provider == null || !provider.enabled()) {
            return "disabled";
        }
        if (!provider.hasApiKey()) {
            return "missing_api_key";
        }
        if (!provider.toolCallingVerified() || !provider.structuredOutputVerified()) {
            return "capabilities_unverified";
        }
        if (provider.requiresEvaluationMode() && !evaluationMode) {
            return "evaluation_only";
        }
        return "ready";
    }

    @AssertTrue(message = "ford.ai.providers.fallback-order must contain unique allowlisted fallback provider IDs")
    public boolean isFallbackOrderValid() {
        return fallbackOrder != null
                && fallbackOrder.stream().allMatch(ALLOWED_FALLBACK_PROVIDERS::contains)
                && fallbackOrder.stream().distinct().count() == fallbackOrder.size();
    }

    @AssertTrue(message = "ford.ai.providers.catalog may contain only allowlisted provider IDs with endpoint and model when enabled")
    public boolean isProviderConfigurationValid() {
        return catalog != null && catalog.entrySet().stream().allMatch(entry -> {
            if (!ALLOWED_FALLBACK_PROVIDERS.contains(entry.getKey()) || entry.getValue() == null) {
                return false;
            }
            var provider = entry.getValue();
            return !provider.enabled()
                    || (hasApprovedEndpoint(entry.getKey(), provider.baseUrl()) && !isBlank(provider.model()));
        });
    }

    private static boolean hasApprovedEndpoint(String providerId, String baseUrl) {
        if (isBlank(baseUrl)) {
            return false;
        }
        try {
            var uri = URI.create(baseUrl);
            var expectedHost = switch (providerId) {
                case "groq" -> "api.groq.com";
                case "gemini" -> "generativelanguage.googleapis.com";
                case "cloudflare" -> "api.cloudflare.com";
                case "openrouter" -> "openrouter.ai";
                default -> "";
            };
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            return !"cloudflare".equals(providerId)
                    || uri.getPath() != null && uri.getPath().matches("/client/v4/accounts/[A-Fa-f0-9]{32}/ai/v1/?");
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class Provider {

        private boolean enabled;
        private String baseUrl;
        private String model;
        private String apiKey;
        private boolean toolCallingVerified;
        private boolean structuredOutputVerified;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String baseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String model() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String apiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean hasApiKey() {
            return !isBlank(apiKey);
        }

        public boolean toolCallingVerified() {
            return toolCallingVerified;
        }

        public void setToolCallingVerified(boolean toolCallingVerified) {
            this.toolCallingVerified = toolCallingVerified;
        }

        public boolean structuredOutputVerified() {
            return structuredOutputVerified;
        }

        public void setStructuredOutputVerified(boolean structuredOutputVerified) {
            this.structuredOutputVerified = structuredOutputVerified;
        }

        public boolean ready() {
            return enabled && hasApiKey() && toolCallingVerified && structuredOutputVerified;
        }

        public boolean requiresEvaluationMode() {
            return "openrouter/free".equalsIgnoreCase(model);
        }
    }
}
