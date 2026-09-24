package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.port.out.SystemPromptRepository;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.application.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiChatCompletionAdapterTest {

    @Test
    void usesTheCurrentRuntimeSystemPrompt() {
        var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("respuesta");
        var prompts = new SystemPromptService((SystemPromptRepository) new EmptyPromptRepository(),
                "runtime prompt", event -> { });
        var adapter = new SpringAiChatCompletionAdapter(client, "ollama", prompts);

        var response = adapter.complete(new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(),
                "Hola", "BOT_ACTIVE", false), new ToolContext(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.content()).isEqualTo("respuesta");
    }

    private static final class EmptyPromptRepository implements SystemPromptRepository {
        @Override
        public java.util.Optional<com.ford.service.ai.application.SystemPromptSnapshot> find() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.ford.service.ai.application.SystemPromptSnapshot> replace(
                String content, long expectedVersion, String updatedBy) {
            return java.util.Optional.empty();
        }
    }
}
