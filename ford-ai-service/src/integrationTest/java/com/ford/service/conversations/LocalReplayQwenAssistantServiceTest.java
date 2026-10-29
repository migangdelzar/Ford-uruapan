package com.ford.service.conversations;

import com.ford.service.ai.api.AssistantTurn;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.AssistantService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalReplayQwenAssistantServiceTest {
    @Test
    void routesOnlyExactNamedComparisonPairsToQwen() {
        var standard = mock(AssistantService.class);
        var qwen = mock(AssistantService.class);
        var expected = new AssistantTurn("Qwen response", com.ford.service.ai.application.Intent.GENERAL,
                false, List.of(), "ollama", "qwen-local");
        when(qwen.respond(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(expected);
        var service = new LocalReplayQwenAssistantService(qwen);
        var context = new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID());

        var result = service.respond(context,
                "De las dos versiones, Ranger 2027 XL Gasolina 4x2 y Ranger 2027 XLT Gasolina 4x2, "
                        + "¿cuál tiene el precio publicado menor?");

        assertThat(result).isSameAs(expected);
        verify(qwen).respond(context,
                "De las dos versiones, Ranger 2027 XL Gasolina 4x2 y Ranger 2027 XLT Gasolina 4x2, "
                        + "¿cuál tiene el precio publicado menor?");
        verify(standard, never()).respond(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void routesNonComparisonTurnsToQwenInPrefetchedReplay() {
        var standard = mock(AssistantService.class);
        var qwen = mock(AssistantService.class);
        var expected = new AssistantTurn("Qwen response", com.ford.service.ai.application.Intent.GENERAL,
                false, List.of(), "ollama", "qwen-local");
        when(qwen.respond(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(expected);
        var service = new LocalReplayQwenAssistantService(qwen);
        var context = new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID());

        assertThat(service.respond(context, "¿Qué dato de seguridad aparece para esa misma versión?"))
                .isSameAs(expected);

        verify(qwen).respond(context, "¿Qué dato de seguridad aparece para esa misma versión?");
        verify(standard, never()).respond(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
