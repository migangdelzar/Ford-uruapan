package com.ford.service.ai;

import com.ford.service.ai.configuration.KnowledgeRagAdvisorConfiguration;
import com.ford.service.ai.adapter.out.springai.KnowledgeRagAdvisorFactory;
import com.ford.service.knowledge.application.KnowledgeRetrieval;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowledgeRagAdvisorConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeRagAdvisorConfiguration.class)
            .withBean(KnowledgeRetrieval.class, () -> mock(KnowledgeRetrieval.class));

    @Test
    void registersAdvisorFactoryWithoutEnablingOptionalReranking() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KnowledgeRagAdvisorFactory.class);
        });
    }

    @Test
    void rejectsRerankingWhenTheConfiguredTypeSafeClientIsUnavailable() {
        runner.withPropertyValues("ford.knowledge.rag.reranker-enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("reranking is enabled but TypeSafe is unavailable"));
    }
}
