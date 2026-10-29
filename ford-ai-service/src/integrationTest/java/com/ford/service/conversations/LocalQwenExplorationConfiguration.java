package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.AssistantScopePolicy;
import com.ford.service.ai.application.CatalogFactResponseRenderer;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.DeterministicCatalogAnswerService;
import com.ford.service.ai.configuration.ClassificationProperties;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.catalog.api.CatalogPort;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;
import java.util.Set;

/** Test-only raw Qwen behavior. Production intent and answer policies are not changed. */
@TestConfiguration(proxyBeanMethods = false)
class LocalQwenExplorationConfiguration {

    @Bean
    @Primary
    ConversationDecisionService rawQwenDecisionService() {
        ConversationDecisionPort neutral = _ -> ConversationDecision.deterministicDefault();
        return new ConversationDecisionService(neutral, neutral, new ClassificationProperties()) {
            @Override
            public ConversationDecision decide(
                    com.ford.service.ai.application.ClassificationContext context) {
                return ConversationDecision.deterministicDefault();
            }

            @Override
            public boolean hasConfidentLeadEvidence(ConversationDecision decision) {
                return false;
            }
        };
    }

    @Bean
    @Primary
    DecisionPolicy rawQwenDecisionPolicy() {
        return new DecisionPolicy() {
            @Override
            public Set<String> allowedToolNames(ConversationDecision decision, Set<String> availableTools) {
                return availableTools == null ? Set.of() : Set.copyOf(availableTools);
            }

            @Override
            public boolean requiresVolatileInformation(ConversationDecision decision) {
                return false;
            }
        };
    }

    @Bean
    @Primary
    AssistantScopePolicy rawQwenScopePolicy(ToolAuthorizationPolicy tools) {
        return new AssistantScopePolicy(new IntentRouter(), tools.availableReadOnlyToolNames()) {
            @Override
            public String reason(String userText, ConversationDecision decision, String memoryContext) {
                return userText == null || userText.isBlank() ? "blank_input" : "allowed";
            }
        };
    }

    @Bean
    @Primary
    DeterministicCatalogAnswerService rawQwenDeterministicCatalogAnswer(
            CatalogPort catalog, CatalogFactResponseRenderer renderer, ObjectMapper objectMapper) {
        return new DeterministicCatalogAnswerService(catalog, renderer, objectMapper) {
            @Override
            public Optional<AssistantResponse> tryAnswer(AssistantRequest request) {
                return Optional.empty();
            }
        };
    }
}
