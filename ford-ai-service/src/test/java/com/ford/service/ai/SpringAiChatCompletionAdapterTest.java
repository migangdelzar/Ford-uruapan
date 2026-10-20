package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.adapter.out.springai.KnowledgeRagAdvisorFactory;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantGeneration;
import com.ford.service.ai.api.ResponseEvidenceStatus;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneyTopic;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.InvalidAssistantResponseException;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.CatalogFactResponseRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import org.springframework.core.io.DefaultResourceLoader;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class SpringAiChatCompletionAdapterTest {

    @Test
    void doesNotExposeConfiguredToolsWhenDecisionHasNoEvidenceOrNoToolFamily() {
        assertNoToolsAreExposed(new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.FINANCE_ESTIMATE, SensitiveDataLevel.NONE, List.of()));
        assertNoToolsAreExposed(new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.NONE, SensitiveDataLevel.NONE, List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "GENERAL", 1.0,
                        "rules_intent", "rules", "deterministic", 0, true))));
    }

    @Test
    void addsSpringAiRagAdvisorOnlyForKnowledgeAndWithholdsWithoutEvidence() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"Le vehículo tiene respaldo en la documentación aprobada.\",\"modelConfidence\":0.9}");
        var retrieval = mock(KnowledgeRetrieval.class);
        var ragFactory = new KnowledgeRagAdvisorFactory(retrieval, List.of());
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(callback("approved_knowledge_search"), callback("get_vehicle"))),
                new DecisionPolicy(), null, new com.ford.service.ai.application.AssistantResponseQualityPolicy(),
                new ObjectMapper(), new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true, ragFactory);
        var knowledge = new ConversationDecision(ConversationIntent.KNOWLEDGE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.KNOWLEDGE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());
        var vehicle = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, testEvidence());
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(tenantId, conversationId,
                "¿Qué cubre la garantía?", "BOT_ACTIVE", null, false, knowledge), null))
                .isInstanceOf(AssistantTurnUnavailableException.class);
        verify(requestSpec).advisors(any(Advisor[].class));
        assertToolNames(requestSpec, "get_vehicle");

        org.mockito.Mockito.clearInvocations(requestSpec);
        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(tenantId, conversationId,
                "¿Qué precio tiene la Explorer?", "BOT_ACTIVE", null, false, vehicle), null))
                .isInstanceOf(AssistantTurnUnavailableException.class);
        verify(requestSpec, never()).advisors(any(Advisor[].class));
    }

    @Test
    void requiresCatalogToolEvidenceForExactFactsOnTheKnowledgeRoute() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var retrieval = mock(KnowledgeRetrieval.class);
        when(client.prompt()).thenReturn(requestSpec);
        var ragFactory = new KnowledgeRagAdvisorFactory(retrieval, List.of());
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(callback("approved_knowledge_search"))),
                new DecisionPolicy(), null, new com.ford.service.ai.application.AssistantResponseQualityPolicy(),
                new ObjectMapper(), new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true, ragFactory);
        var knowledge = new ConversationDecision(ConversationIntent.KNOWLEDGE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.KNOWLEDGE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Qué motor tiene la Bronco?", "BOT_ACTIVE", null, false, knowledge), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
        verify(requestSpec, never()).call();
    }

    @Test
    void allowsNarrativeKnowledgeQuestionsThroughSpringAiRagWithoutTheDuplicateKnowledgeTool() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"La garantía explica las coberturas aprobadas.\",\"modelConfidence\":0.9}");
        var retrieval = mock(KnowledgeRetrieval.class);
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(callback("approved_knowledge_search"))),
                new DecisionPolicy(), null, new com.ford.service.ai.application.AssistantResponseQualityPolicy(),
                new ObjectMapper(), new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true,
                new KnowledgeRagAdvisorFactory(retrieval, List.of()));
        var knowledge = new ConversationDecision(ConversationIntent.KNOWLEDGE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.KNOWLEDGE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Cómo funciona la garantía Ford?", "BOT_ACTIVE", null, false, knowledge), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));

        verify(requestSpec).advisors(any(Advisor[].class));
        verify(requestSpec).call();
        verify(requestSpec, never()).tools(any(Object[].class));
    }

    private static void assertNoToolsAreExposed(ConversationDecision decision) {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Puedo ayudarle con información general.");
        var configured = callback("vehicle_snapshot_search");
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(configured)));

        adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Me explicas esto?", "BOT_ACTIVE", null, false, decision), null);

        verify(requestSpec, never()).tools(any(Object[].class));
        verify(requestSpec).call();
    }

    @Test
    void returnsMcpPriceAndSourceLinkWhenTheModelFailsAfterTheToolCall() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var getVehicle = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("get_vehicle").description("vehicle").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "{\"model\":\"Explorer\",\"year\":2027,\"priceDisplay\":\"$1,193,600\","
                        + "\"modelPageUrl\":\"https://www.forduruapan.mx/EXPLORER/27/\"}";
            }
        };
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(new IllegalStateException("provider unavailable"));

        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, testEvidence());
        var renderer = new CatalogFactResponseRenderer(new DefaultResourceLoader(), new ObjectMapper());
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(getVehicle)), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), renderer);

        var result = adapter.complete(new AssistantRequest(tenantId, conversationId,
                        "¿Qué precio tiene Explorer 2027?", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId));

        assertThat(result.content()).contains("Explorer 2027", "$1,193,600",
                "https://www.forduruapan.mx/EXPLORER/27/");
        assertThat(result.evidenceStatus()).isEqualTo(ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
    }

    @Test
    void doesNotReturnFailureTextWhenCatalogLookupFailsWithoutVerifiedFacts() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var failingTool = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("get_vehicle").description("vehicle").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new AssistantTurnUnavailableException(AssistantTurnUnavailableException.Disposition.RETRY);
            }
        };
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });

        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, testEvidence());
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(failingTool)), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP),
                new CatalogFactResponseRenderer(new DefaultResourceLoader(), new ObjectMapper()));

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(tenantId, conversationId,
                "¿Qué precio tiene Explorer 2027?", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId)))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.RETRY));
    }

    @Test
    void doesNotClaimNoPublishedPriceWhenTheModelNeverCallsTheAvailableTool() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"La Ranger cuesta $850,000.\",\"modelConfidence\":0.99}");
        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, testEvidence());
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(callback("get_vehicle"))), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP),
                new CatalogFactResponseRenderer(new DefaultResourceLoader(), new ObjectMapper()));

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Qué precio tiene la Ranger?", "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
    }

    @Test
    void doesNotSendOnlySourceLinksWhenTheModelFailsForAnUnrenderableExactFact() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var getVehicle = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("get_vehicle").description("vehicle").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "{\"model\":\"Maverick Hybrid\",\"technicalSheetUrl\":"
                        + "\"https://www.forduruapan.mx/MAVERICK/26/Ficha-Tecnica/\"}";
            }
        };
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(new IllegalStateException("provider unavailable"));

        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, testEvidence());
        var renderer = new CatalogFactResponseRenderer(new DefaultResourceLoader(), new ObjectMapper());
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(getVehicle)), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), renderer);

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(tenantId, conversationId,
                        "¿Qué rendimiento tiene Maverick Hybrid?", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
    }

    @Test
    void replacesModelPriceAnswerWithApprovedTemplateAndMcpSourceLink() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var toolInvocations = new java.util.concurrent.atomic.AtomicInteger();
        var getVehicle = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("get_vehicle").description("vehicle").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                toolInvocations.incrementAndGet();
                return "{\"model\":\"Explorer\",\"year\":2027,\"priceDisplay\":\"$1,193,600\","
                        + "\"modelPageUrl\":\"https://www.forduruapan.mx/EXPLORER/27/?utm_source=test\"}";
            }
        };
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"La Explorer cuesta $1.\"}");

        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, testEvidence());
        var renderer = new CatalogFactResponseRenderer(new DefaultResourceLoader(), new ObjectMapper());
        var adapter = new SpringAiChatCompletionAdapter(client, "openrouter",
                new ToolAuthorizationPolicy(List.of(getVehicle)), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), renderer);

        var result = adapter.complete(new AssistantRequest(tenantId, conversationId,
                        "¿Qué precio tiene Explorer 2027?", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId));

        assertThat(toolInvocations).hasValue(1);
        assertThat(result.content()).contains("Explorer 2027", "$1,193,600",
                "https://www.forduruapan.mx/EXPLORER/27/")
                .doesNotContain("utm_source");
        assertThat(result.evidenceStatus()).isEqualTo(ResponseEvidenceStatus.READ_ONLY_TOOL_RETURNED);
    }

    @Test
    void appendsExactComparisonUrlReturnedByMcpToTheModelAnswer() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var comparison = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("compare_vehicles").description("comparison").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "{\"modelPageUrl\":\"https://www.forduruapan.mx/EXPEDITION/27/\","
                        + "\"comparisons\":[{\"comparisonUrl\":\"https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/?ref=x\"}]}";
            }
        };
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"La fuente muestra diferencias de equipamiento.\"}");

        var renderer = new CatalogFactResponseRenderer(new DefaultResourceLoader(), new ObjectMapper());
        var adapter = new SpringAiChatCompletionAdapter(client, "openrouter",
                new ToolAuthorizationPolicy(List.of(comparison)), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), renderer);
        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, testEvidence());

        var result = adapter.complete(new AssistantRequest(tenantId, conversationId,
                        "Compare Expedition King Ranch Max with competitors", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId));

        assertThat(result.content()).contains("La fuente muestra diferencias de equipamiento.",
                "🔎 *Enlaces útiles*",
                "https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/")
                .doesNotContain("?ref=x", "[Ver");
    }

    @Test
    void attachesReadOnlyToolsAndTenantContextToTheSpringAiRequest() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var callback = callback("vehicle_snapshot_search");
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        var proposal = new LeadJourneyProposal(LeadJourneyTopic.VEHICLE_DISCOVERY, List.of(), null);
        when(responseSpec.content()).thenReturn("{\"answer\":\"Respuesta de catálogo.\",\"journeyProposal\":{\"currentTopic\":\"VEHICLE_DISCOVERY\",\"facts\":[],\"pendingQuestionKey\":null}}");

        var adapter = new SpringAiChatCompletionAdapter(client, "ollama",
                new ToolAuthorizationPolicy(List.of(callback)));
        var decision = new ConversationDecision(ConversationIntent.INVENTORY, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());
        var result = adapter.complete(new AssistantRequest(tenantId, conversationId,
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId));

        assertThat(result.content()).isEqualTo("Respuesta de catálogo.");
        assertThat(result.journeyProposal()).isEqualTo(proposal);
        assertToolNames(requestSpec, "vehicle_snapshot_search");
        verify(requestSpec).toolContext(Map.of(
                "tenantId", tenantId.toString(),
                "conversationId", conversationId.toString(),
                "locale", "es-MX"));
    }

    @Test
    void includesDurableMemoryBeforeTheCurrentUserMessage() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Respuesta con memoria.");

        var memory = "Resumen: Busca una Ranger\n"
                + "Historial reciente:\n- usuario: Hola\n"
                + "Hechos relevantes:\n- color: azul";

        new SpringAiChatCompletionAdapter(client, "ollama")
                .complete(new AssistantRequest(tenantId, conversationId, "¿Qué versión?", "BOT_ACTIVE", memory, false),
                        null);

        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue().indexOf("Busca una Ranger"))
                .isLessThan(system.getValue().indexOf("Hola"));
        assertThat(system.getValue().indexOf("Hola"))
                .isLessThan(system.getValue().indexOf("azul"));
    }

    @Test
    void appendsDynamicDecisionAndMemoryContextToTheConfiguredSystemPrompt() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var promptService = mock(SystemPromptService.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(promptService.currentContent()).thenReturn("CUSTOM FORD PROMPT");
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Respuesta contextual.");

        var decision = new ConversationDecision(ConversationIntent.OFFERS, LeadTemperature.WARM,
                LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.OFFERS,
                SensitiveDataLevel.NONE, testEvidence());
        var adapter = new SpringAiChatCompletionAdapter(client, "openrouter",
                new ToolAuthorizationPolicy(List.of(callback("list_promotions"))),
                new DecisionPolicy(), promptService);

        adapter.complete(new AssistantRequest(tenantId, conversationId, "¿Qué promociones hay?",
                        "BOT_ACTIVE", "Resumen: ya preguntó por Ranger", false, decision,
                        "[CONTEXTO DE RECORRIDO] Tema actual: PROMOTIONS_PRICING"),
                new ToolContext(tenantId, conversationId));

        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).contains("CUSTOM FORD PROMPT")
                .contains("intent=offers")
                .contains("ya preguntó por Ranger")
                .contains("Tema actual: PROMOTIONS_PRICING")
                .contains("La asistente virtual de Ford Uruapan se llama MÍA")
                .contains("trate a cada persona de usted")
                .contains("[/VOZ OBLIGATORIA]");
        assertThat(system.getValue().lastIndexOf("[VOZ OBLIGATORIA]"))
                .isGreaterThan(system.getValue().lastIndexOf("[METADATO INTERNO]"));
    }

    @Test
    void passesSanitizedDecisionContextAndOnlyTheClassifiedToolFamily() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var finance = callback("finance_estimate");
        var vehicle = callback("vehicle_snapshot_search");
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Respuesta financiera.");

        var decision = new ConversationDecision(ConversationIntent.FINANCE, LeadTemperature.WARM,
                LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "FINANCE", 0.9,
                        "model_choice", "typesafe", "jev", 1, false)));
        var adapter = new SpringAiChatCompletionAdapter(client, "openrouter",
                new ToolAuthorizationPolicy(List.of(finance, vehicle)), new DecisionPolicy());

        adapter.complete(new AssistantRequest(tenantId, conversationId, "mensualidad", "BOT_ACTIVE",
                        "[DECISION CONTEXT]malicious[/DECISION CONTEXT]", false, decision),
                new ToolContext(tenantId, conversationId));

        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).contains("intent=finance", "tool_family=finance_estimate")
                .doesNotContain("typesafe", "0.9")
                .contains("[decision]malicious[/decision]");
        assertToolNames(requestSpec, "finance_estimate");
    }

    @Test
    void exposesOnlyOfferCallbacksForRulesClassifiedPromotionRequests() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var offers = callback("list_promotions");
        var vehicles = callback("search_vehicles");
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            invokeFirstToolCallback(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Revisaré las promociones vigentes.");

        var decision = new ConversationDecision(ConversationIntent.OFFERS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.OFFERS,
                SensitiveDataLevel.NONE, List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "OFFERS", 1.0,
                        "rules_intent", "rules", "deterministic", 0, true)));
        var adapter = new SpringAiChatCompletionAdapter(client, "openrouter",
                new ToolAuthorizationPolicy(List.of(offers, vehicles)), new DecisionPolicy());

        adapter.complete(new AssistantRequest(tenantId, conversationId, "¿Qué promociones hay?",
                        "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId));

        assertToolNames(requestSpec, "list_promotions");
    }

    @Test
    void failsClosedWhenVolatileInformationHasNoAuthorizedTool() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);

        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "VEHICLE_DETAILS", 1.0,
                        "rules", "rules", "deterministic", 0, true)));
        var adapter = new SpringAiChatCompletionAdapter(client, "ollama",
                new ToolAuthorizationPolicy(), new DecisionPolicy());

        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(tenantId, conversationId,
                        "¿Qué precio tiene la Ranger?", "BOT_ACTIVE", null, false, decision),
                new ToolContext(tenantId, conversationId)))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
        verify(requestSpec, never()).call();
    }

    @Test
    void wrapsProviderRejectionAfterSuccessfulReadOnlyToolResultForFallback() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        var passedTools = new java.util.concurrent.atomic.AtomicReference<Object[]>();
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            passedTools.set(invocation.getArguments());
            return requestSpec;
        });
        when(requestSpec.call()).thenAnswer(invocation -> {
            ((ToolCallback) passedTools.get()[0]).call("{}");
            return responseSpec;
        });
        var providerFailure = mock(com.openai.errors.OpenAIServiceException.class);
        when(providerFailure.statusCode()).thenReturn(400);
        when(providerFailure.headers()).thenReturn(com.openai.core.http.Headers.builder().build());
        when(responseSpec.content()).thenThrow(providerFailure);

        var decision = new ConversationDecision(ConversationIntent.INVENTORY,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_SEARCH, SensitiveDataLevel.NONE, testEvidence());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "groq",
                new ToolAuthorizationPolicy(List.of(callback("vehicle_snapshot_search"))))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Qué precio tiene la Ranger?", "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(com.ford.service.ai.application.ProviderFollowupRejectedException.class)
                .hasCause(providerFailure);
    }

    @Test
    void propagatesRetryableStructuredOutputFailureSoProviderRouterCanFallback() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(
                tools.jackson.databind.exc.MismatchedInputException.from(
                        null, AssistantGeneration.class, "invalid structured response"));

        var decision = new ConversationDecision(ConversationIntent.KNOWLEDGE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.KNOWLEDGE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());
        var adapter = new SpringAiChatCompletionAdapter(client, "cloudflare",
                new ToolAuthorizationPolicy(List.of(callback("approved_knowledge_search"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.complete(
                new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "¿Qué rendimiento tiene la Ranger?",
                        "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(tools.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    void acceptsPlainTextAfterAToolCallWithoutProviderStructuredOutput() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("La Ranger está disponible en los resultados consultados.");

        var result = new SpringAiChatCompletionAdapter(client, "groq",
                new ToolAuthorizationPolicy(List.of(callback("search_vehicles"))))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", false), null);

        assertThat(result.content()).isEqualTo("La Ranger está disponible en los resultados consultados.");
        assertThat(result.journeyProposal()).isNull();
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).doesNotContain("objeto JSON válido");
        verify(responseSpec).content();
        verify(responseSpec, never()).entity(AssistantGeneration.class);
    }

    @Test
    void refusesVolatileAnswerWhenNoAuthorizedToolResultWasReturned() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"La Ranger está disponible.\",\"modelConfidence\":0.99}");
        var decision = new ConversationDecision(ConversationIntent.INVENTORY, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());

        assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "groq",
                new ToolAuthorizationPolicy(List.of(callback("search_vehicles"))))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
    }

    @Test
    void sendsNoMessageWhenTheReadOnlyToolFailsWithoutVerifiedFacts() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var passedTools = new java.util.concurrent.atomic.AtomicReference<Object[]>();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            passedTools.set(invocation.getArguments());
            return requestSpec;
        });
        when(requestSpec.call()).thenAnswer(invocation -> {
            ((ToolCallback) passedTools.get()[0]).call("{}");
            return responseSpec;
        });
        var failedTool = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("search_vehicles").description("search").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("catalog timeout");
            }
        };
        var decision = new ConversationDecision(ConversationIntent.INVENTORY, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());

        assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(failedTool)))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
    }

    @Test
    void doesNotAnswerFromGeneralKnowledgeWhenReadOnlyToolFails() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var passedTools = new java.util.concurrent.atomic.AtomicReference<Object[]>();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            passedTools.set(invocation.getArguments());
            return requestSpec;
        });
        when(requestSpec.call()).thenAnswer(invocation -> {
            ((ToolCallback) passedTools.get()[0]).call("{}");
            return responseSpec;
        });
        when(responseSpec.content()).thenReturn("""
                {"answer":"Un sistema híbrido combina un motor de combustión y uno eléctrico para apoyar la conducción.",
                 "modelConfidence":0.70}
                """);
        var failedTool = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("search_vehicles").description("search").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("catalog timeout");
            }
        };
        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, testEvidence());

        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(failedTool)));
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        assertThatThrownBy(() -> adapter.complete(new AssistantRequest(tenantId, conversationId,
                "¿Cómo funciona en general la tecnología híbrida y qué ventajas ofrece?", "BOT_ACTIVE", null,
                false, decision), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
        verify(responseSpec, never()).content();
    }

    @Test
    void withholdsGeneralModelKnowledgeWhenItsConfidenceIsBelowSeventyPercent() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var passedTools = new java.util.concurrent.atomic.AtomicReference<Object[]>();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            passedTools.set(invocation.getArguments());
            return requestSpec;
        });
        when(requestSpec.call()).thenAnswer(invocation -> {
            ((ToolCallback) passedTools.get()[0]).call("{}");
            return responseSpec;
        });
        when(responseSpec.content()).thenReturn("""
                {"answer":"Un sistema híbrido combina un motor de combustión y uno eléctrico.",
                 "modelConfidence":0.69}
                """);
        var failedTool = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("search_vehicles").description("search").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("catalog timeout");
            }
        };
        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, testEvidence());

        assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(failedTool)))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Qué significa que un vehículo sea híbrido?", "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
    }

    @Test
    void leavesTurnPendingWhenTheReadOnlyToolReturnsNoStructuredResult() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var passedTools = new java.util.concurrent.atomic.AtomicReference<Object[]>();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenAnswer(invocation -> {
            passedTools.set(invocation.getArguments());
            return requestSpec;
        });
        when(requestSpec.call()).thenAnswer(invocation -> {
            ((ToolCallback) passedTools.get()[0]).call("{}");
            return responseSpec;
        });
        when(responseSpec.content()).thenReturn("Sí, la Ranger está disponible por $850,000 en 2027.");
        var emptyTool = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("search_vehicles").description("search").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "";
            }
        };
        var decision = new ConversationDecision(ConversationIntent.INVENTORY, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_SEARCH,
                SensitiveDataLevel.NONE, testEvidence());

        assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of(emptyTool)))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", null, false, decision), null))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.RETRY));
    }

    @Test
    void retainsModelSelfConfidenceOnlyAsInternalResponseMetadata() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"answer\":\"Le explico cómo funciona una versión híbrida.\",\"modelConfidence\":0.62}");
        var decision = new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());

        var result = new SpringAiChatCompletionAdapter(client, "groq")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Qué significa híbrida?", "BOT_ACTIVE", null, false, decision), null);

        assertThat(result.content()).isEqualTo("Le explico cómo funciona una versión híbrida.");
        assertThat(result.content()).doesNotContain("0.62", "62%");
        assertThat(result.modelConfidence()).isEqualTo(0.62);
        assertThat(result.evidenceStatus()).isEqualTo(ResponseEvidenceStatus.NOT_REQUIRED);
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).contains("modelConfidence", "porcentaje para el cliente");
    }

    @Test
    void removesAConfidenceMarkerLeakedInsideTheCustomerAnswer() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Hola.\n\n[modelConfidence: 0.9]");

        var result = new SpringAiChatCompletionAdapter(client, "deepseek")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "Hola", "BOT_ACTIVE", false), null);

        assertThat(result.content()).isEqualTo("Hola.");
        assertThat(result.content()).doesNotContain("modelConfidence", "0.9");
    }

    @Test
    void acceptsPlainTextWhenStructuredResponseConversionCannotParseIt() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Hola, ¿en qué puedo ayudarle?");

        var result = new SpringAiChatCompletionAdapter(client, "deepseek")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "Hola", "BOT_ACTIVE", false), null);

        assertThat(result.content()).isEqualTo("Hola, ¿en qué puedo ayudarle?");
    }

    @Test
    void doesNotStartASecondCompletionWhenTheProviderReturnsNoText() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(" ");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "deepseek")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "Hola", "BOT_ACTIVE", false), null))
                .isInstanceOf(InvalidAssistantResponseException.class)
                .hasFieldOrPropertyWithValue("reason", "empty_response");

        verify(requestSpec, times(1)).call();
        verify(responseSpec, never()).entity(AssistantGeneration.class);
    }

    @Test
    void parsesAssistantGenerationJsonReturnedAsTextAfterAToolCall() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"answer\":\"Encontré la Ranger.\",\"journeyProposal\":{\"currentTopic\":\"VEHICLE_DISCOVERY\",\"facts\":[],\"pendingQuestionKey\":null}}");

        var result = new SpringAiChatCompletionAdapter(client, "cloudflare",
                new ToolAuthorizationPolicy(List.of(callback("search_vehicles"))))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", false), null);

        assertThat(result.content()).isEqualTo("Encontré la Ranger.");
        assertThat(result.journeyProposal()).isNotNull();
        assertThat(result.journeyProposal().currentTopic()).isEqualTo(LeadJourneyTopic.VEHICLE_DISCOVERY);
    }

    @Test
    void keepsACompleteCustomerAnswerWhenOptionalJourneyMetadataIsTruncated() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("""
                {"answer":"Claro, con gusto. ¿Qué versión de Territory te gustaría comparar?",
                 "journeyProposal":{"currentTopic":"VEHICLE_DISCOVERY","facts":[{"key":"model"
                """);

        var result = new SpringAiChatCompletionAdapter(client, "deepseek")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "Sí", "BOT_ACTIVE", false), null);

        assertThat(result.content()).isEqualTo("Claro, con gusto. ¿Qué versión de Territory te gustaría comparar?");
        assertThat(result.journeyProposal()).isNull();
    }

    @Test
    void rejectsMalformedJsonInsteadOfSendingItAsCustomerTextAfterAToolCall() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{invalid json");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "groq",
                new ToolAuthorizationPolicy(List.of(callback("search_vehicles"))))
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Hay Ranger disponible?", "BOT_ACTIVE", false), null))
                .isInstanceOf(InvalidAssistantResponseException.class);
    }

    @Test
    void returnsProviderResponseWhenResponseVerificationIsDisabled() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("La Mustang tiene una cabina amplia.");
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true, null,
                new com.ford.service.ai.application.EvidenceGroundedResponsePolicy(), false);

        var response = adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Qué opinas?", "BOT_ACTIVE", false), null);

        assertThat(response.content()).isEqualTo("La Mustang tiene una cabina amplia.");
        verify(requestSpec, times(1)).call();
    }

    @Test
    void repairsAProviderResponseThatLeaksToolPayloads() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"answer\":\"{\\\"query\\\":\\\"Ford Lobo\\\"}\"}",
                "La Lobo Raptor ofrece el rendimiento disponible en la ficha vigente.");

        var result = new SpringAiChatCompletionAdapter(client, "openrouter")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Qué rendimiento tiene la Lobo Raptor?", "BOT_ACTIVE", false), null);

        assertThat(result.content()).contains("Lobo Raptor");
        verify(requestSpec, times(2)).call();
        var systems = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(2)).system(systems.capture());
        assertThat(systems.getAllValues()).allSatisfy(system ->
                assertThat(system).contains("La asistente virtual de Ford Uruapan se llama MÍA")
                        .contains("trate a cada persona de usted")
                        .endsWith("[/VOZ OBLIGATORIA]"));
    }

    @Test
    void doesNotRepairAnInvalidFallbackAnswerMoreThanOnce() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("¿Qué modelo le interesa y cuánto planea invertir?");
        var adapter = new SpringAiChatCompletionAdapter(client, "openrouter",
                new ToolAuthorizationPolicy(), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.complete(
                        new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), "Quiero cotizar",
                                "BOT_ACTIVE", false),
                        new ToolContext(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(InvalidAssistantResponseException.class)
                .hasFieldOrPropertyWithValue("reason", "multiple_questions");
        verify(requestSpec, times(1)).call();
    }

    @Test
    void stopsAfterOneRepairWhenTheProviderKeepsReturningInvalidOutput() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"answer\":\"{\\\"query\\\":\\\"Ford Lobo\\\"}\"}",
                "{\"answer\":\"{\\\"query\\\":\\\"Ford Lobo\\\"}\"}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpringAiChatCompletionAdapter(client, "openrouter")
                .complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "¿Qué rendimiento tiene la Lobo Raptor?", "BOT_ACTIVE", false), null))
                .isInstanceOf(InvalidAssistantResponseException.class);
        verify(requestSpec, times(2)).call();
    }

    @Test
    void repairsANeverPromiseAnswerUsingTheEvidenceGroundedPolicy() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"answer\":\"Le garantizo entrega inmediata hoy mismo.\",\"modelConfidence\":0.9}",
                "{\"answer\":\"Puedo revisar con un asesor las opciones de entrega disponibles.\",\"modelConfidence\":0.9}");
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of()), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true, null,
                new com.ford.service.ai.application.EvidenceGroundedResponsePolicy());
        var finance = new ConversationDecision(ConversationIntent.FINANCE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, testEvidence());

        var result = adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "¿Cuándo me entregan el auto?", "BOT_ACTIVE", null, false, finance), null);

        assertThat(result.content()).contains("asesor");
        verify(requestSpec, times(2)).call();
        var systems = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(2)).system(systems.capture());
        assertThat(systems.getAllValues().get(1)).contains("No prometa entregas");
    }

    @Test
    void acceptsApprovedAgencyFactsFromTheConfiguredSystemPrompt() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        var promptService = mock(SystemPromptService.class);
        when(promptService.currentContent()).thenReturn("""
                Información fija de la agencia:
                - Dirección: Paseo Lázaro Cárdenas y Av. Juárez, La Magdalena, Uruapan, Michoacán, C.P. 60080.
                - Teléfono/WhatsApp: 452 519 8300.
                - Horario: lunes a viernes de 9:00 a.m. a 7:00 p.m.
                """);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"answer\":\"Estamos en La Magdalena, C.P. 60080. Teléfono 452 519 8300, de 9:00 a.m. a 7:00 p.m.\","
                        + "\"modelConfidence\":0.9}");
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of()), new DecisionPolicy(), promptService,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true, null,
                new com.ford.service.ai.application.EvidenceGroundedResponsePolicy(), true);
        var general = new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, testEvidence());

        var result = adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "Hola, ¿cómo están?", "BOT_ACTIVE", null, false, general), null);

        assertThat(result.content()).contains("452 519 8300");
        verify(requestSpec, times(1)).call();
    }

    @Test
    void returnsSafeClarificationWhenEvidenceRepairStillFails() {
        var client = mock(ChatClient.class);
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "{\"answer\":\"El precio es 999999 pesos.\",\"modelConfidence\":0.9}",
                "{\"answer\":\"El precio es 999999 pesos.\",\"modelConfidence\":0.9}");
        var adapter = new SpringAiChatCompletionAdapter(client, "deepseek",
                new ToolAuthorizationPolicy(List.of()), new DecisionPolicy(), null,
                new com.ford.service.ai.application.AssistantResponseQualityPolicy(), new ObjectMapper(),
                new com.ford.service.observability.DefaultAiObservationService(
                        io.micrometer.observation.ObservationRegistry.NOOP), null, true, null,
                new com.ford.service.ai.application.EvidenceGroundedResponsePolicy());
        var general = new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, testEvidence());

        var result = adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "Hola", "BOT_ACTIVE", null, false, general), null);

        assertThat(result.content()).contains("detalle").doesNotContain("999999");
        verify(requestSpec, times(2)).call();
    }

    private static List<com.ford.service.ai.application.ClassificationEvidence> testEvidence() {
        return List.of(new com.ford.service.ai.application.ClassificationEvidence(
                com.ford.service.ai.application.ClassifierType.INTENT, "TEST", 1.0,
                "test_intent", "test", "test", 0, true));
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    private static void invokeFirstToolCallback(Object candidate) {
        if (candidate instanceof ToolCallback callback) {
            callback.call("{}");
        } else if (candidate instanceof Object[] callbacks) {
            for (var item : callbacks) {
                if (item instanceof ToolCallback callback) {
                    callback.call("{}");
                    return;
                }
                if (item instanceof Object[]) {
                    invokeFirstToolCallback(item);
                    return;
                }
            }
        }
    }

    private static void assertToolNames(ChatClient.ChatClientRequestSpec requestSpec, String... expectedNames) {
        var callbacks = ArgumentCaptor.forClass(Object[].class);
        verify(requestSpec).tools(callbacks.capture());
        var actualNames = Arrays.stream(callbacks.getValue())
                .map(callback -> ((ToolCallback) callback).getToolDefinition().name())
                .toList();
        assertThat(actualNames).containsExactly(expectedNames);
    }
}
