package com.ford.service.conversations.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class ConversationAsyncConfiguration {

    @Bean(name = "conversationTurnExecutor")
    public ThreadPoolTaskExecutor conversationTurnExecutor(ConversationProperties properties) {
        var workflow = properties.workflow();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workflow.turnConcurrency());
        executor.setMaxPoolSize(workflow.turnConcurrency());
        executor.setQueueCapacity(workflow.turnQueueCapacity());
        executor.setThreadNamePrefix("conversation-turn-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean
    ConversationPublicationBacklog conversationPublicationBacklog(JdbcClient jdbcClient) {
        return new ConversationPublicationBacklog(jdbcClient);
    }

    @Bean
    ConversationWorkerMetrics conversationWorkerMetrics(
            @Qualifier("conversationTurnExecutor") ThreadPoolTaskExecutor executor,
            ConversationPublicationBacklog backlog,
            MeterRegistry meters) {
        executor.initialize();
        Gauge.builder("ford.conversation.worker.active", executor,
                value -> value.getThreadPoolExecutor().getActiveCount()).register(meters);
        Gauge.builder("ford.conversation.worker.pool.size", executor,
                value -> value.getThreadPoolExecutor().getPoolSize()).register(meters);
        Gauge.builder("ford.conversation.worker.queue.size", executor,
                value -> value.getThreadPoolExecutor().getQueue().size()).register(meters);
        Gauge.builder("ford.conversation.publication.incomplete", backlog,
                ConversationPublicationBacklog::incompleteCount).register(meters);
        Gauge.builder("ford.conversation.publication.oldest.wait.seconds", backlog,
                ConversationPublicationBacklog::oldestWaitSeconds).register(meters);
        return new ConversationWorkerMetrics(executor, backlog);
    }

    record ConversationWorkerMetrics(ThreadPoolTaskExecutor executor, ConversationPublicationBacklog backlog) {
    }

    static final class ConversationPublicationBacklog {
        private final JdbcClient jdbc;

        private ConversationPublicationBacklog(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        double incompleteCount() {
            return jdbc.sql("select count(*) from event_publication where completion_date is null")
                    .query(Long.class).single().doubleValue();
        }

        double oldestWaitSeconds() {
            var publicationDate = jdbc.sql("select min(publication_date) from event_publication where completion_date is null")
                    .query((resultSet, rowNum) -> resultSet.getTimestamp(1) == null
                            ? null : resultSet.getTimestamp(1).toInstant())
                    .optional().orElse(null);
            return publicationDate == null ? 0.0 : Math.max(0.0,
                    java.time.Duration.between(publicationDate, Instant.now()).toMillis() / 1000.0);
        }
    }
}
