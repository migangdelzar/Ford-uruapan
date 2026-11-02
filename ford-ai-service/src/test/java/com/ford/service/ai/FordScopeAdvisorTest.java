package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.FordScopeAdvisor;
import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.AssistantScopePolicy;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FordScopeAdvisorTest {

    private static final String MEMORY_CONTEXT_PARAMETER = "ford.conversation.memoryContext";
    private static final String FOLLOW_UP = "Y para manejarla todos los días, ¿qué debo tener en cuenta sobre esa preferencia?";
    private static final String FORD_HISTORY = "Historial reciente:\n- usuario: Estoy considerando una Ford Ranger";

    private final FordScopeAdvisor advisor = new FordScopeAdvisor(
            new AssistantScopePolicy(new IntentRouter()));

    @Test
    void allowsContextualFollowUpWhenTrustedConversationMemoryContainsFordContext() {
        var request = request(FOLLOW_UP, FORD_HISTORY);
        var chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(null);

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(request);
    }

    @Test
    void completionAdapterPassesConversationMemoryToTheScopeAdvisor() {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().model("test").build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Puede considerar cómo se adapta a sus traslados cotidianos.")))));
        var client = ChatClient.builder(model).defaultAdvisors(advisor).build();
        var adapter = new SpringAiChatCompletionAdapter(client, "ollama");
        var decision = new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, List.of());
        var request = new AssistantRequest(UUID.randomUUID(), UUID.randomUUID(), FOLLOW_UP, "BOT_ACTIVE",
                FORD_HISTORY, false, decision);

        var response = adapter.complete(request, null);

        assertThat(response.content()).isEqualTo("Puede considerar cómo se adapta a sus traslados cotidianos.");
        verify(model).call(any(Prompt.class));
    }

    @Test
    void blocksContextualFollowUpWhenConversationMemoryIsAbsent() {
        var request = request(FOLLOW_UP, null);
        var chain = mock(CallAdvisorChain.class);

        var response = advisor.adviseCall(request, chain);

        verify(chain, never()).nextCall(request);
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo(new AssistantScopePolicy(new IntentRouter()).scopeResponse());
    }

    @Test
    void keepsBlockingUnrelatedRequestsEvenWhenConversationMemoryContainsFordContext() {
        var request = request("Explícame la teoría de la relatividad", FORD_HISTORY);
        var chain = mock(CallAdvisorChain.class);

        var response = advisor.adviseCall(request, chain);

        verify(chain, never()).nextCall(request);
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo(new AssistantScopePolicy(new IntentRouter()).scopeResponse());
    }

    private static ChatClientRequest request(String userText, String memoryContext) {
        return new ChatClientRequest(new Prompt(new UserMessage(userText)), memoryContext == null
                ? Map.of() : Map.of(MEMORY_CONTEXT_PARAMETER, memoryContext));
    }
}
