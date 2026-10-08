package com.ford.service.conversations.application;

import com.ford.service.channels.api.InboundMessageAccepted;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAsyncConfigurationTest {

    @Test
    void defaultsConversationWorkersToOneWithBoundedQueueAndDurableRejection() {
        var properties = new ConversationProperties();
        var executor = new ConversationAsyncConfiguration().conversationTurnExecutor(properties);
        executor.initialize();
        try {
            var pool = executor.getThreadPoolExecutor();
            assertThat(pool.getCorePoolSize()).isEqualTo(1);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(8);
            assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void conversationListenerSelectsDedicatedExecutorWithoutChangingOtherListeners() throws Exception {
        var listener = ConversationWorkflow.class.getMethod("on", InboundMessageAccepted.class);

        assertThat(listener.getAnnotation(ApplicationModuleListener.class)).isNotNull();
        assertThat(listener.getAnnotation(Async.class).value()).isEqualTo("conversationTurnExecutor");
    }

    @Test
    void allowsOnlyTheApprovedOneTwoOrFiveWorkerRolloutRange() {
        var workflow = new ConversationProperties.Workflow();
        workflow.setTurnConcurrency(2);
        assertThat(workflow.turnConcurrency()).isEqualTo(2);
        workflow.setTurnConcurrency(5);
        assertThat(workflow.turnConcurrency()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> workflow.setTurnConcurrency(6));
    }
}
