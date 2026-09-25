package com.ford.service.ai.configuration;

import com.ford.service.ai.adapter.in.tool.FinanceEstimateTool;
import com.ford.service.ai.adapter.in.tool.KnowledgeTools;
import com.ford.service.ai.adapter.in.tool.VehicleTools;
import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.adapter.out.typesafe.TypeSafeConversationDecisionAdapter;
import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.DefaultAssistantOrchestrator;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.AssistantScopePolicy;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.LeadQualificationPolicy;
import com.ford.service.ai.application.SpringAiAssistantService;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationDecisionService;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.client.RestClient;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeProperties;
import org.springaicommunity.typesafe.advisor.JevGuardrailAdvisor;
import org.springaicommunity.typesafe.advisor.JevSelfRefineAdvisor;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.question.Noul;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Composition root marker for Spring AI adapters and read-only tool wiring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ClassificationProperties.class, TypeSafeProperties.class, McpProperties.class})
public class SpringAiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfiguration.class);

    @Bean
    @Conditional(TypeSafeApiKeyCondition.class)
    TypeSafeClient typeSafeClient(TypeSafeProperties properties,
                                   ObjectProvider<RestClient.Builder> restClientBuilders) {
        var requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.getTimeout());
        var restClientBuilder = restClientBuilders
                .getIfAvailable(RestClient::builder)
                .clone()
                .requestFactory(requestFactory);
        return TypeSafeClient.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .defaultModel(properties.getModel())
                .timeout(properties.getTimeout())
                .retryPolicy(properties.toRetryPolicy())
                .restClientBuilder(restClientBuilder)
                .build();
    }

    @Bean
    IntentRouter intentRouter() {
        return new IntentRouter();
    }

    @Bean
    ConversationDecisionPort conversationDecisionPort(ObjectProvider<TypeSafeClient> clients,
                                                      ObjectProvider<TypeSafeProperties> typeSafeProperties,
                                                      ClassificationProperties properties,
                                                      IntentRouter intentRouter) {
        var rules = new RulesConversationDecisionAdapter(intentRouter);
        var client = clients.getIfAvailable();
        var providerProperties = typeSafeProperties.getIfAvailable();
        if (!properties.enabled()
                || "disabled".equalsIgnoreCase(properties.mode())
                || !"typesafe".equalsIgnoreCase(properties.provider())
                || client == null
                || providerProperties == null
                || providerProperties.getApiKey() == null
                || providerProperties.getApiKey().isBlank()) {
            return rules;
        }
        return new TypeSafeConversationDecisionAdapter(client, properties);
    }

    @Bean
    ConversationDecisionService conversationDecisionService(ConversationDecisionPort primary,
                                                            IntentRouter intentRouter,
                                                            ClassificationProperties properties) {
        var rules = new RulesConversationDecisionAdapter(intentRouter);
        return new ConversationDecisionService(primary, rules, properties);
    }

    @Bean
    DecisionPolicy decisionPolicy() {
        return new DecisionPolicy();
    }

    @Bean
    AssistantScopePolicy assistantScopePolicy(IntentRouter intentRouter) {
        return new AssistantScopePolicy(intentRouter);
    }

    @Bean
    LeadQualificationPolicy leadQualificationPolicy() {
        return new LeadQualificationPolicy();
    }

    @Bean
    ToolAuthorizationPolicy toolAuthorizationPolicy(ObjectProvider<VehicleCatalog> catalog,
                                                    ObjectProvider<FinanceOperations> finance,
                                                    ObjectProvider<FinanceTermsRepository> terms,
                                                    ObjectProvider<KnowledgeRetrieval> knowledge,
                                                    ObjectProvider<SyncMcpToolCallbackProvider> mcpProviders,
                                                    McpProperties mcpProperties) {
        var toolObjects = new ArrayList<Object>();
        var vehicleCatalog = catalog.getIfAvailable();
        if (vehicleCatalog != null && !mcpProperties.requiredForVolatileInformation()) {
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
        var callbacks = new ArrayList<ToolCallback>();
        if (!toolObjects.isEmpty()) {
            callbacks.addAll(List.of(ToolCallbacks.from(toolObjects.toArray())));
        }
        var mcpProvider = mcpProviders.getIfAvailable();
        if (mcpProperties.enabled() && mcpProvider != null) {
            try {
                callbacks.addAll(List.of(mcpProvider.getToolCallbacks()).stream()
                        .filter(callback -> mcpProperties.readOnlyToolNames()
                                .contains(callback.getToolDefinition().name().toLowerCase(java.util.Locale.ROOT)))
                        .toList());
            }
            catch (RuntimeException exception) {
                log.warn("Ford MCP tools unavailable; volatile information remains fail-closed: exceptionType={}",
                        exception.getClass().getSimpleName());
            }
        }
        if (callbacks.isEmpty()) {
            return new ToolAuthorizationPolicy();
        }
        return new ToolAuthorizationPolicy(callbacks);
    }

    @Bean
    ChatCompletionPort chatCompletionPort(ObjectProvider<OpenAiChatModel> cloudModel,
                                           ObjectProvider<OllamaChatModel> localModel,
                                         AiProperties properties,
                                         ToolAuthorizationPolicy toolPolicy,
                                         ModelProviderPolicy providerPolicy,
                                         DecisionPolicy decisionPolicy,
                                         AssistantScopePolicy scopePolicy,
                                         ObjectProvider<SystemPromptService> systemPromptServices,
                                           ClassificationProperties classificationProperties,
                                           ObjectProvider<TypeSafeClient> typeSafeClients,
                                           ObjectProvider<TypeSafeProperties> typeSafeProperties) {
        var providers = new ArrayList<ProviderChatCompletionPort>();
        var systemPromptService = systemPromptServices.getIfAvailable();
        var typeSafeClient = typeSafeClients.getIfAvailable();
        var typeSafeConfigured = usableTypeSafeConfiguration(typeSafeClient, typeSafeProperties.getIfAvailable(),
                classificationProperties);
        var openAi = cloudModel.getIfAvailable();
        if (properties.cloudEnabled() && openAi != null) {
            providers.add(new SpringAiChatCompletionAdapter(
                    chatClient(openAi, classificationProperties,
                            typeSafeConfigured ? typeSafeClient : null, scopePolicy),
                    properties.cloudChatProvider(), toolPolicy, decisionPolicy, systemPromptService));
        }
        var ollama = localModel.getIfAvailable();
        if (properties.localEnabled() && ollama != null) {
            providers.add(new SpringAiChatCompletionAdapter(
                    chatClient(ollama, classificationProperties,
                            typeSafeConfigured ? typeSafeClient : null, scopePolicy),
                    properties.localChatProvider(), toolPolicy, decisionPolicy, systemPromptService));
        }
        return providers.isEmpty()
                ? new UnavailableChatCompletionPort()
                : new ProviderRoutingChatCompletionPort(providers, providerPolicy);
    }

    private static ChatClient chatClient(ChatModel model,
                                         ClassificationProperties properties,
                                         TypeSafeClient typeSafeClient,
                                         AssistantScopePolicy scopePolicy) {
        var builder = ChatClient.builder(model);
        builder.defaultAdvisors(new com.ford.service.ai.adapter.out.springai.FordScopeAdvisor(scopePolicy));
        if (typeSafeClient == null) {
            return builder.build();
        }
        if (properties.selfRefineEnabled()) {
            var judge = JevJudge.builder(typeSafeClient)
                    .noul("is_grounded", Noul.of(
                            "La respuesta está respaldada por el contexto y las herramientas disponibles."),
                            properties.minConfidence())
                    .noul("is_safe", Noul.of(
                            "La respuesta es segura y apropiada para un asistente de ventas automotrices."),
                            properties.minConfidence())
                    .minConfidence(properties.minConfidence())
                    .failOnInconclusive(true)
                    .build();
            builder.defaultAdvisors(JevSelfRefineAdvisor.builder()
                    .judge(judge)
                    .maxRepeatAttempts(properties.selfRefineMaxAttempts())
                    .failOnExhaustedAttempts(false)
                    .build());
        }
        if (properties.guardrailsEnabled()) {
            builder.defaultAdvisors(JevGuardrailAdvisor.builder(typeSafeClient)
                    .blockOnReview(true)
                    .build());
        }
        return builder.build();
    }

    private static boolean usableTypeSafeConfiguration(TypeSafeClient client,
                                                       TypeSafeProperties providerProperties,
                                                       ClassificationProperties properties) {
        var apiKey = providerProperties == null ? null : providerProperties.getApiKey();
        return client != null
                && properties.enabled()
                && !"disabled".equalsIgnoreCase(properties.mode())
                && "typesafe".equalsIgnoreCase(properties.provider())
                && apiKey != null
                && !apiKey.isBlank()
                && !apiKey.equalsIgnoreCase("replace-with-secret")
                && !apiKey.equalsIgnoreCase("test-only-placeholder");
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
                                      AiProperties properties,
                                      ConversationDecisionService decisionService,
                                      DecisionPolicy decisionPolicy,
                                      AssistantScopePolicy scopePolicy,
                                      LeadQualificationPolicy leadQualificationPolicy) {
        return new SpringAiAssistantService(orchestrator,
                handoffPolicy.getIfAvailable(() -> new HumanHandoffPolicy(true)), providerPolicy,
                properties.logProviderErrors(), decisionService, decisionPolicy, scopePolicy,
                leadQualificationPolicy);
    }
}
