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
import com.ford.service.ai.application.port.out.UnavailableChatCompletionPort;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderRoutingChatCompletionPort;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.catalog.application.VehicleCatalog;
import com.ford.service.finance.application.FinanceOperations;
import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
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
    ToolAuthorizationPolicy toolAuthorizationPolicy(ObjectProvider<VehicleCatalog> catalog,
                                                    ObjectProvider<FinanceOperations> finance,
                                                    ObjectProvider<FinanceTermsRepository> terms,
                                                    ObjectProvider<KnowledgeRetrieval> knowledge) {
        var toolObjects = new ArrayList<Object>();
        var vehicleCatalog = catalog.getIfAvailable();
        if (vehicleCatalog != null) {
            toolObjects.add(new VehicleTools(vehicleCatalog));
        }
        var financeOperations = finance.getIfAvailable();
        var financeTerms = terms.getIfAvailable();
        if (financeOperations != null && financeTerms != null) {
            toolObjects.add(new FinanceEstimateTool(financeOperations, financeTerms));
        }
        var knowledgeRetrieval = knowledge.getIfAvailable();
        if (knowledgeRetrieval != null) {
            toolObjects.add(new KnowledgeTools(knowledgeRetrieval));
        }
        if (toolObjects.isEmpty()) {
            return new ToolAuthorizationPolicy();
        }
        return new ToolAuthorizationPolicy(List.of(ToolCallbacks.from(toolObjects.toArray())));
    }

    @Bean
    ChatCompletionPort chatCompletionPort(ObjectProvider<OpenAiChatModel> cloudModel,
                                           ObjectProvider<OllamaChatModel> localModel,
                                           AiProperties properties,
                                           ToolAuthorizationPolicy toolPolicy,
                                           ModelProviderPolicy providerPolicy) {
        var providers = new ArrayList<ProviderChatCompletionPort>();
        var openAi = cloudModel.getIfAvailable();
        if (properties.cloudEnabled() && openAi != null) {
            providers.add(new SpringAiChatCompletionAdapter(
                    ChatClient.builder(openAi).build(), properties.cloudChatProvider(), toolPolicy));
        }
        var ollama = localModel.getIfAvailable();
        if (properties.localEnabled() && ollama != null) {
            providers.add(new SpringAiChatCompletionAdapter(
                    ChatClient.builder(ollama).build(), properties.localChatProvider(), toolPolicy));
        }
        return providers.isEmpty()
                ? new UnavailableChatCompletionPort()
                : new ProviderRoutingChatCompletionPort(providers, providerPolicy);
    }

    @Bean
    AssistantOrchestrator assistantOrchestrator(IntentRouter intentRouter,
                                                ToolAuthorizationPolicy toolPolicy,
                                                ChatCompletionPort completion) {
        return new DefaultAssistantOrchestrator(intentRouter, toolPolicy, completion);
    }

    @Bean
    AssistantService assistantService(AssistantOrchestrator orchestrator,
                                      ModelProviderPolicy providerPolicy,
                                      ObjectProvider<HumanHandoffPolicy> handoffPolicy,
                                      AiProperties properties) {
        return new SpringAiAssistantService(orchestrator,
                handoffPolicy.getIfAvailable(() -> new HumanHandoffPolicy(true)), providerPolicy,
                properties.logProviderErrors());
    }
}
