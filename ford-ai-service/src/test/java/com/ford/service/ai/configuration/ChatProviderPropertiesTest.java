package com.ford.service.ai.configuration;

import jakarta.validation.Validation;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatProviderPropertiesTest {

    @Test
    void productionUsesDeepSeekAsItsOnlyChatProviderByDefault() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var environment = new StandardEnvironment();
        loader.load("application-prod", new ClassPathResource("application-prod.yml"))
                .forEach(environment.getPropertySources()::addFirst);
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        var ai = Binder.get(environment).bind("ford.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("production AI configuration is missing"));
        var providers = Binder.get(environment).bind("ford.ai.providers", Bindable.of(ChatProviderProperties.class))
                .orElseThrow(() -> new IllegalStateException("production provider configuration is missing"));

        assertThat(ai.cloudChatProvider()).isEqualTo("openai");
        assertThat(ai.cloudEnabled()).isTrue();
        assertThat(ai.localEnabled()).isFalse();
        assertThat(ai.providerMaxAttempts()).isEqualTo(1);
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.deepseek.com");
        assertThat(environment.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("deepseek-flash");
        assertThat(providers.fallbackOrder()).isEmpty();
        assertThat(providers.eligibleFallbackIds()).isEmpty();
    }

    @Test
    void doesNotEnableOpenRouterAsAProductionFallbackEvenWhenCredentialsArePresent() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-credentials",
                Map.of("OPENROUTER_API_KEY", "test-key",
                        "ford.ai.providers.catalog.openrouter.api-key", "test-key")));
        loader.load("application-prod", new ClassPathResource("application-prod.yml"))
                .forEach(environment.getPropertySources()::addFirst);
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        var properties = Binder.get(environment).bind("ford.ai.providers",
                Bindable.of(ChatProviderProperties.class))
                .orElseThrow(() -> new IllegalStateException("production AI provider configuration is missing"));

        assertThat(properties.fallbackOrder()).isEmpty();
        assertThat(properties.providerStatus()).isEmpty();
        assertThat(properties.eligibleFallbackIds()).isEmpty();
    }

    @Test
    void defaultsFallbackOrderToTheApprovedProviderSequence() {
        var properties = new ChatProviderProperties();

        assertThat(properties.fallbackOrder()).containsExactly("cloudflare", "groq", "gemini", "openrouter");
    }

    @Test
    void doesNotMarkEnabledProviderAvailableWhenItsCredentialIsMissing() {
        var properties = new ChatProviderProperties();
        var groq = new ChatProviderProperties.Provider();
        groq.setEnabled(true);
        groq.setBaseUrl("https://api.groq.com/openai/v1");
        groq.setModel("model-under-evaluation");
        groq.setToolCallingVerified(true);
        groq.setStructuredOutputVerified(true);
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("groq", groq)));

        assertThat(properties.eligibleFallbackIds()).isEmpty();
        assertThat(properties.providerStatus()).containsEntry("groq", "missing_api_key");
        assertThat(properties.providerStatus().toString()).doesNotContain("model-under-evaluation");
    }

    @Test
    void makesEnabledProviderEligibleOnlyWhenItsCredentialIsPresent() {
        var properties = new ChatProviderProperties();
        var groq = provider("secret-value");
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("groq", groq)));

        assertThat(properties.eligibleFallbackIds()).containsExactly("groq");
        assertThat(properties.providerStatus()).containsEntry("groq", "ready");
        assertThat(properties.providerStatus().toString()).doesNotContain("secret-value");
    }

    @Test
    void keepsProviderOutOfFallbackUntilBothCapabilitiesHaveBeenVerified() {
        var properties = new ChatProviderProperties();
        var groq = new ChatProviderProperties.Provider();
        groq.setEnabled(true);
        groq.setBaseUrl("https://api.groq.com/openai/v1");
        groq.setModel("model-under-evaluation");
        groq.setApiKey("secret-value");
        groq.setToolCallingVerified(true);
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("groq", groq)));

        assertThat(properties.eligibleFallbackIds()).isEmpty();
        assertThat(properties.providerStatus()).containsEntry("groq", "capabilities_unverified");
    }

    @Test
    void keepsOpenRouterFreeModelOutOfNormalFallbackOutsideEvaluationMode() {
        var properties = new ChatProviderProperties();
        var openRouter = provider("secret-value");
        openRouter.setModel("openrouter/free");
        openRouter.setBaseUrl("https://openrouter.ai/api/v1");
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("openrouter", openRouter)));

        assertThat(properties.eligibleFallbackIds()).isEmpty();
        assertThat(properties.providerStatus()).containsEntry("openrouter", "evaluation_only");

        properties.setEvaluationMode(true);

        assertThat(properties.eligibleFallbackIds()).containsExactly("openrouter");
        assertThat(properties.providerStatus()).containsEntry("openrouter", "ready");
    }

    @Test
    void reportsUnknownProviderIdsAndDuplicateFallbackIdsAsInvalidConfiguration() {
        var properties = new ChatProviderProperties();
        properties.setFallbackOrder(List.of("groq", "groq"));
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("unknown", new ChatProviderProperties.Provider())));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(properties);

            assertThat(violations)
                    .extracting(violation -> violation.getMessage())
                    .contains("ford.ai.providers.fallback-order must contain unique allowlisted fallback provider IDs",
                            "ford.ai.providers.catalog may contain only allowlisted provider IDs with endpoint and model when enabled");
        }
    }

    @Test
    void requiresEndpointAndModelForEnabledProviderEvenWhenCredentialIsMissing() {
        var properties = new ChatProviderProperties();
        var groq = new ChatProviderProperties.Provider();
        groq.setEnabled(true);
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("groq", groq)));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test
    void rejectsCloudflarePlaceholderAccountEndpointWhenEnabled() {
        var properties = new ChatProviderProperties();
        var cloudflare = provider("cloudflare-key");
        cloudflare.setBaseUrl("https://api.cloudflare.com/client/v4/accounts/unconfigured/ai/v1");
        cloudflare.setModel("@cf/meta/llama-3.3-70b-instruct-fp8-fast");
        properties.setCatalog(new LinkedHashMap<>(java.util.Map.of("cloudflare", cloudflare)));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(properties))
                    .extracting(violation -> violation.getMessage())
                    .contains("ford.ai.providers.catalog may contain only allowlisted provider IDs with endpoint and model when enabled");
        }
    }

    private static ChatProviderProperties.Provider provider(String key) {
        var provider = new ChatProviderProperties.Provider();
        provider.setEnabled(true);
        provider.setBaseUrl("https://api.groq.com/openai/v1");
        provider.setModel("model-under-evaluation");
        provider.setApiKey(key);
        provider.setToolCallingVerified(true);
        provider.setStructuredOutputVerified(true);
        return provider;
    }
}
