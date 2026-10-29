package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.AssistantScopePolicy;
import com.ford.service.ai.application.IntentRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FordScopeAdvisorTest {

    @Test
    void shortCircuitsClearlyUnrelatedRequests() {
        var chain = mock(CallAdvisorChain.class);
        var advisor = new FordScopeAdvisor(new AssistantScopePolicy(new IntentRouter()));

        var response = advisor.adviseCall(request("Write a poem about volcanoes"), chain);

        assertThat(response.chatResponse().getResult().getOutput().getText())
                .contains("Ford");
        verify(chain, never()).nextCall(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void forwardsFordQuestionsToTheNextAdvisor() {
        var chain = mock(CallAdvisorChain.class);
        var expected = mock(ChatClientResponse.class);
        when(chain.nextCall(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        var advisor = new FordScopeAdvisor(new AssistantScopePolicy(new IntentRouter()));
        var request = request("What is the price of the Ford Ranger?");

        assertThat(advisor.adviseCall(request, chain)).isSameAs(expected);
        verify(chain).nextCall(request);
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(new Prompt(new UserMessage(text)), Map.of());
    }
}
