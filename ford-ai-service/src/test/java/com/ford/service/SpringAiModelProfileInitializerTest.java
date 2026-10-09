package com.ford.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiModelProfileInitializerTest {

    @Test
    void enablesOpenAiAutoConfigurationForTheDefaultDeepSeekProfile() {
        var context = context(new MockEnvironment());

        new SpringAiModelProfileInitializer().initialize(context);

        assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("openai");
    }

    @Test
    void enablesOllamaAutoConfigurationForTheLocalProfile() {
        var context = context(new MockEnvironment().withProperty("FORD_AI_MODEL_PROFILE", "local"));

        new SpringAiModelProfileInitializer().initialize(context);

        assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("ollama");
    }

    @Test
    void rejectsUnknownProfilesBeforeSpringChoosesAModelAutoConfiguration() {
        var context = context(new MockEnvironment().withProperty("FORD_AI_MODEL_PROFILE", "unknown"));

        assertThatThrownBy(() -> new SpringAiModelProfileInitializer().initialize(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FORD_AI_MODEL_PROFILE");
    }

    private static GenericApplicationContext context(MockEnvironment environment) {
        var context = new GenericApplicationContext();
        context.setEnvironment(environment);
        return context;
    }
}
