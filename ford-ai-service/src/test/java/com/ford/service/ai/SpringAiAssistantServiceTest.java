package com.ford.service.ai;

import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantOrchestrator;
import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.Intent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiAssistantServiceTest {

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
    void convertsProviderFailureIntoDeterministicHumanHandoff() {
        var orchestrator = mock(AssistantOrchestrator.class);
        when(orchestrator.respond(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("provider timeout"));
        var service = new SpringAiAssistantService(orchestrator, new DefaultModelProviderPolicy(List.of()));

        var turn = service.respond(new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID()),
                "¿Qué precio tiene la Ranger?");

        assertThat(turn.text()).isEqualTo("En este momento te comunicaré con un asesor humano.");
        assertThat(turn.intent()).isEqualTo(Intent.CATALOG);
        assertThat(turn.requestsHuman()).isTrue();
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
}
