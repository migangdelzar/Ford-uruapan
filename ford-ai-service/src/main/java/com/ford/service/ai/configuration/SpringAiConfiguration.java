package com.ford.service.ai.configuration;

import com.ford.service.ai.adapter.in.tool.FinanceEstimateTool;
import com.ford.service.ai.adapter.in.tool.KnowledgeTools;
import com.ford.service.ai.adapter.in.tool.VehicleTools;
import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.DefaultAssistantOrchestrator;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.SpringAiAssistantService;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderRoutingChatCompletionPort;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.catalog.application.VehicleCatalog;
import com.ford.service.finance.application.FinanceOperations;
import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.support.ToolCallbacks;

import java.util.ArrayList;
import java.util.List;

/** Composition root marker for Spring AI adapters and read-only tool wiring. */
@Configuration(proxyBeanMethods = false)
public class SpringAiConfiguration {

    @Bean
    IntentRouter intentRouter() {
        return new IntentRouter();
    }

    @Bean
    @ConditionalOnBean(VehicleCatalog.class)
    VehicleTools vehicleTools(VehicleCatalog catalog) {
        return new VehicleTools(catalog);
    }

    @Bean
    @ConditionalOnBean({FinanceOperations.class, FinanceTermsRepository.class})
    FinanceEstimateTool financeEstimateTool(FinanceOperations finance, FinanceTermsRepository termsRepository) {
        return new FinanceEstimateTool(finance, termsRepository);
    }

    @Bean
    @ConditionalOnBean(KnowledgeRetrieval.class)
    KnowledgeTools knowledgeTools(KnowledgeRetrieval retrieval) {
        return new KnowledgeTools(retrieval);
    }

    @Bean
    ToolAuthorizationPolicy toolAuthorizationPolicy(ObjectProvider<VehicleTools> vehicleTools,
                                                    ObjectProvider<FinanceEstimateTool> financeTools,
                                                    ObjectProvider<KnowledgeTools> knowledgeTools) {
        var toolObjects = new ArrayList<Object>();
        vehicleTools.ifAvailable(toolObjects::add);
        financeTools.ifAvailable(toolObjects::add);
        knowledgeTools.ifAvailable(toolObjects::add);
        if (toolObjects.isEmpty()) {
            return new ToolAuthorizationPolicy();
        }
        return new ToolAuthorizationPolicy(List.of(ToolCallbacks.from(toolObjects.toArray())));
    }

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    ProviderChatCompletionPort springAiChatCompletionPort(ChatClient.Builder builder,
                                                          ToolAuthorizationPolicy toolPolicy,
                                                          @Value("${spring.ai.model.chat:ollama}") String provider) {
        return new SpringAiChatCompletionAdapter(builder.build(), provider, toolPolicy);
    }

    @Bean
    @ConditionalOnBean({ChatClient.Builder.class, ModelProviderPolicy.class})
    ChatCompletionPort chatCompletionPort(List<ProviderChatCompletionPort> providers,
                                           ModelProviderPolicy providerPolicy) {
        return new ProviderRoutingChatCompletionPort(providers, providerPolicy);
    }

    @Bean
    @ConditionalOnBean(ChatCompletionPort.class)
    AssistantOrchestrator assistantOrchestrator(IntentRouter intentRouter,
                                                ToolAuthorizationPolicy toolPolicy,
                                                ChatCompletionPort completion) {
        return new DefaultAssistantOrchestrator(intentRouter, toolPolicy, completion);
    }

    @Bean
    @ConditionalOnBean(AssistantOrchestrator.class)
    AssistantService assistantService(AssistantOrchestrator orchestrator,
                                      com.ford.service.ai.application.ModelProviderPolicy providerPolicy) {
        return new SpringAiAssistantService(orchestrator, providerPolicy);
    }
}
