package com.ford.service.ai;

import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AiProviderFailureDiagnostics;
import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.Intent;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.configuration.ClassificationProperties;
import com.ford.service.shared.HumanHandoffPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.SpringAiAssistantService;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class SpringAiAssistantServiceTest {

    @Test
    void classifiesBeforeGenerationAndPropagatesTheDecision() {
        var orchestrator = mock(AssistantOrchestrator.class);
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantResponse("La mensualidad depende del plazo.", false, "ollama", List.of()));
        var decision = new ConversationDecision(ConversationIntent.FINANCE, LeadTemperature.WARM,
                LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, List.of());
        ConversationDecisionPort classifier = context -> decision;
        var decisionService = new ConversationDecisionService(classifier, classifier, new ClassificationProperties());
        var service = new SpringAiAssistantService(orchestrator, new HumanHandoffPolicy(false),
                new DefaultModelProviderPolicy(List.of()), false, decisionService);

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "Quiero una mensualidad");

        var request = org.mockito.ArgumentCaptor.forClass(com.ford.service.ai.api.AssistantRequest.class);
        verify(orchestrator).respond(request.capture());
        assertThat(request.getValue().decision()).isSameAs(decision);
        assertThat(turn.decision()).isSameAs(decision);
        assertThat(turn.intent()).isEqualTo(Intent.FINANCE);
    }

    @Test
    void blocksClearlyUnrelatedRequestsBeforeCallingTheModel() {
        var orchestrator = mock(AssistantOrchestrator.class);
        var service = new SpringAiAssistantService(orchestrator,
                new HumanHandoffPolicy(false), new DefaultModelProviderPolicy(List.of()));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "Escribe un poema sobre volcanes");

        assertThat(turn.text()).contains("vehículos Ford");
        assertThat(turn.provider()).isEqualTo("scope-guard");
        verify(orchestrator, org.mockito.Mockito.never()).respond(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void keepsAQualifiedButInformationalLeadWithTheAssistant() {
        var orchestrator = mock(AssistantOrchestrator.class);
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantResponse("Le explico las opciones disponibles.", false,
                        "ollama", List.of()));
        var decision = new ConversationDecision(ConversationIntent.FINANCE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, List.of());
        ConversationDecisionPort classifier = context -> decision;
        var service = new SpringAiAssistantService(orchestrator, new HumanHandoffPolicy(true),
                new DefaultModelProviderPolicy(List.of()), false,
                new ConversationDecisionService(classifier, classifier, new ClassificationProperties()));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID(),
                        "Historial reciente:\n- usuario: Me interesa la Ford Ranger\n- usuario: ¿Puedo financiarla?"),
                "¿Qué opciones tengo este mes?");

        assertThat(turn.decision().leadStage()).isEqualTo(LeadStage.QUALIFIED);
        assertThat(turn.decision().leadTemperature()).isEqualTo(LeadTemperature.WARM);
        assertThat(turn.requestsHuman()).isFalse();
        verify(orchestrator).respond(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesAConfirmedTransactionalIntentToTheAdvisorBeforeGeneration() {
        var orchestrator = mock(AssistantOrchestrator.class);
        var decision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.VEHICLE_DETAILS,
                SensitiveDataLevel.NONE, List.of());
        ConversationDecisionPort classifier = context -> decision;
        var service = new SpringAiAssistantService(orchestrator, new HumanHandoffPolicy(true),
                new DefaultModelProviderPolicy(List.of()), false,
                new ConversationDecisionService(classifier, classifier, new ClassificationProperties()));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "Quiero comprar la Ranger este mes");

        assertThat(turn.requestsHuman()).isTrue();
        assertThat(turn.provider()).isEqualTo("lead-policy");
        assertThat(turn.decision().leadStage()).isEqualTo(LeadStage.QUALIFIED);
        assertThat(turn.decision().leadTemperature()).isEqualTo(LeadTemperature.HOT);
        assertThat(turn.decision().handoffRecommendation())
                .isEqualTo(HandoffRecommendation.ASSISTANT_RECOMMENDED);
        verify(orchestrator, org.mockito.Mockito.never()).respond(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mapsOrchestratedResponseToGroundedAssistantTurn() {
        var orchestrator = mock(AssistantOrchestrator.class);
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantResponse("Hay Ranger disponible.", false, "ollama", List.of("doc-1")));
        var service = new SpringAiAssistantService(orchestrator, new DefaultModelProviderPolicy(List.of(
                new ProviderDescriptor("ollama", new ProviderCapabilities(true, true, true), true,
                        false, "llama3.2"))));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "¿Hay Ranger disponible?");

        assertThat(turn.text()).contains("Ranger");
        assertThat(turn.intent()).isEqualTo(Intent.INVENTORY);
        assertThat(turn.requestsHuman()).isFalse();
        assertThat(turn.sourceIds()).containsExactly("doc-1");
        assertThat(turn.provider()).isEqualTo("ollama");
        assertThat(turn.modelVersion()).isEqualTo("llama3.2");
    }

    @Test
    void convertsProviderFailureIntoBotFallbackWithoutImplicitHandoff() {
        var orchestrator = mock(AssistantOrchestrator.class);
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("provider timeout"));
        var service = new SpringAiAssistantService(orchestrator, new DefaultModelProviderPolicy(List.of()));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "¿Qué precio tiene la Ranger?");

        assertThat(turn.text()).isEqualTo("En este momento te comunicaré con un asesor humano.");
        assertThat(turn.intent()).isEqualTo(Intent.CATALOG);
        assertThat(turn.requestsHuman()).isFalse();
        assertThat(turn.provider()).isEqualTo("fallback");
        assertThat(turn.modelVersion()).isEqualTo("none");
    }

    @Test
    void convertsProviderFailureIntoBotFallbackWhenHandoffIsDisabled() {
        var orchestrator = mock(AssistantOrchestrator.class);
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("provider timeout"));
        var service = new SpringAiAssistantService(orchestrator,
                new HumanHandoffPolicy(false), new DefaultModelProviderPolicy(List.of()));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "¿Qué precio tiene la Ranger?");

        assertThat(turn.text()).isEqualTo(
                "No pude generar una respuesta en este momento. Intenta de nuevo en unos segundos.");
        assertThat(turn.intent()).isEqualTo(Intent.CATALOG);
        assertThat(turn.requestsHuman()).isFalse();
        assertThat(turn.provider()).isEqualTo("fallback");
        assertThat(turn.modelVersion()).isEqualTo("none");
    }

    @Test
    void logsProviderStatusAndRetryMetadataWithoutProviderResponseByDefault() {
        var orchestrator = mock(AssistantOrchestrator.class);
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(429);
        when(failure.headers()).thenReturn(Headers.builder()
                .put("retry-after", "12")
                .put("x-request-id", "req-123")
                .build());
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
        var logger = (Logger) LoggerFactory.getLogger(SpringAiAssistantService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            var service = new SpringAiAssistantService(orchestrator,
                    new HumanHandoffPolicy(false), new DefaultModelProviderPolicy(List.of()));

            service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()), "Hola");

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("status=429", "category=rate_limited", "retryAfter=12", "requestId=req-123")
                            .doesNotContain("responseBody="));
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void providerDiagnosticsDoNotFailWhenTheProviderErrorMetadataIsMalformed() {
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(404);
        when(failure.headers()).thenReturn(Headers.builder().build());
        when(failure.code()).thenThrow(new IllegalStateException("code is invalid"));
        when(failure.type()).thenThrow(new IllegalStateException("type is invalid"));

        assertThatCode(() -> AiProviderFailureDiagnostics.describe(failure, false))
                .doesNotThrowAnyException();
        assertThat(AiProviderFailureDiagnostics.describe(failure, false))
                .contains("category=not_found", "status=404")
                .doesNotContain("responseBody=");
    }
}
