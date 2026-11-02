package com.ford.service.ai.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

final class TypeSafeApiKeyCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var apiKey = context.getEnvironment().getProperty("spring.ai.typesafe.api-key");
        var usable = StringUtils.hasText(apiKey)
                && !"replace-with-secret".equalsIgnoreCase(apiKey.strip())
                && !"test-only-placeholder".equalsIgnoreCase(apiKey.strip());
        return usable
                ? ConditionOutcome.match("TypeSafe API key is configured")
                : ConditionOutcome.noMatch("TypeSafe API key is not configured");
    }
}
