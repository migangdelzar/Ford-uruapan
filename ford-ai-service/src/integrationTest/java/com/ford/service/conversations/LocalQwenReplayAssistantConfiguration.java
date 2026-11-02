package com.ford.service.conversations;

import com.ford.service.ai.application.AssistantPersona;
import com.ford.service.ai.application.AssistantScopePolicy;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.InputSecurityPolicy;
import com.ford.service.ai.application.LeadQualificationPolicy;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.SpringAiAssistantService;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.shared.HumanHandoffPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Runs every opt-in prefetched synthetic replay turn through Qwen. */
@TestConfiguration(proxyBeanMethods = false)
class LocalQwenReplayAssistantConfiguration {
    @Bean
    @Primary
    @ConditionalOnProperty(name = "ford.test.local-qwen-comparison-prefetch.enabled", havingValue = "true")
    AssistantService localReplayQwenAssistantService(
            com.ford.service.ai.application.AssistantOrchestrator orchestrator,
            ObjectProvider<HumanHandoffPolicy> handoffPolicies,
            ModelProviderPolicy providerPolicy,
            AiProperties properties,
            ConversationDecisionService decisionService,
            DecisionPolicy decisionPolicy,
            AssistantScopePolicy scopePolicy,
            LeadQualificationPolicy leadQualificationPolicy,
            InputSecurityPolicy inputSecurityPolicy,
            AssistantPersona assistantPersona) {
        var qwenAssistant = new SpringAiAssistantService(orchestrator,
                handoffPolicies.getIfAvailable(() -> new HumanHandoffPolicy(true)), providerPolicy,
                properties.logProviderErrors(), decisionService, decisionPolicy, scopePolicy,
                leadQualificationPolicy, inputSecurityPolicy, assistantPersona, null,
                properties.responseRepeatCheckEnabled());
        return new LocalReplayQwenAssistantService(qwenAssistant);
    }
}
