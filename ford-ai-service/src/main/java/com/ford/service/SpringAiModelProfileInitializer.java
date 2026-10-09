package com.ford.service;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.Locale;

/** Selects the matching Spring AI chat auto-configuration from the app model profile. */
public final class SpringAiModelProfileInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var profile = context.getEnvironment().getProperty("FORD_AI_MODEL_PROFILE", "deepseek")
                .toLowerCase(Locale.ROOT);
        var chatModel = switch (profile) {
            case "deepseek" -> "openai";
            case "local" -> "ollama";
            default -> throw new IllegalArgumentException(
                    "FORD_AI_MODEL_PROFILE must be deepseek or local");
        };
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("fordAiModelProfile", Map.of("spring.ai.model.chat", chatModel)));
    }
}
