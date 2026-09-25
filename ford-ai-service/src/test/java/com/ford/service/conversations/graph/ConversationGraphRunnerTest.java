package com.ford.service.conversations.graph;

import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationGraphRunner;
import com.ford.service.conversations.application.graph.ConversationTurnOutcome;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.graph.ConversationGraphExecutionException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationGraphRunnerTest {

    @Test
    void processesBotReplyOnceAndTerminatesAtBotRoute() {
        var calls = new AtomicInteger();
        var runner = new ConversationGraphRunner(reference -> {
            calls.incrementAndGet();
            return new ConversationTurnOutcome(ConversationGraphRoute.BOT_REPLY);
        });

        var result = runner.run(reference());

        assertThat(calls).hasValue(1);
        assertThat(result.route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void sendsWarmFollowUpToItsTerminalRouteWithoutReenteringTurnProcessor() {
        var calls = new AtomicInteger();
        var runner = new ConversationGraphRunner(reference -> {
            calls.incrementAndGet();
            return new ConversationTurnOutcome(ConversationGraphRoute.WARM_FOLLOW_UP);
        });

        var result = runner.run(reference());

        assertThat(calls).hasValue(1);
        assertThat(result.route()).isEqualTo(ConversationGraphRoute.WARM_FOLLOW_UP);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void sendsHotAndExplicitHumanRoutesToTheHumanPendingTerminal() {
        var calls = new AtomicInteger();
        var runner = new ConversationGraphRunner(reference -> {
            calls.incrementAndGet();
            return new ConversationTurnOutcome(ConversationGraphRoute.HUMAN_PENDING);
        });

        var result = runner.run(reference());

        assertThat(calls).hasValue(1);
        assertThat(result.route()).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void sendsIneligibleConversationToIgnoredTerminal() {
        var runner = new ConversationGraphRunner(reference ->
                new ConversationTurnOutcome(ConversationGraphRoute.IGNORED));

        var result = runner.run(reference());

        assertThat(result.route()).isEqualTo(ConversationGraphRoute.IGNORED);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void providerFailureDoesNotProduceTerminalSuccessOrInvokeProcessorAgain() {
        var calls = new AtomicInteger();
        var runner = new ConversationGraphRunner(reference -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider unavailable");
        });

        assertThatThrownBy(() -> runner.run(reference()))
                .isInstanceOf(ConversationGraphExecutionException.class)
                .hasMessage("Conversation turn processor failed (IllegalStateException)")
                .hasMessageNotContaining("provider unavailable")
                .hasNoCause();
        assertThat(calls).hasValue(1);
    }

    private static ConversationTurnReference reference() {
        return new ConversationTurnReference(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
