package com.ford.service.ai;

import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ModelRequirements;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.AiExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ModelProviderPolicyTest {

    @Test
    void prefersConfiguredCloudProviderForNonSensitiveStructuredToolRequest() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("ollama", true, true, true, false),
                provider("openai", true, true, true, true)));

        var selected = policy.select(
                new AiExecutionContext(UUID.randomUUID(), false),
                new ModelRequirements(true, true));

        assertThat(selected.id()).isEqualTo("openai");
    }

    @Test
    void selectsLocalProviderWhenSensitiveDataIsPresent() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("openai", true, true, true, true),
                provider("ollama", true, true, true, false)));

        var selected = policy.select(
                new AiExecutionContext(UUID.randomUUID(), true),
                new ModelRequirements(true, true));

        assertThat(selected.id()).isEqualTo("ollama");
    }

    @Test
    void refusesUnsupportedCapabilitiesInsteadOfSilentlyDowngrading() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("openai", true, false, true, true)));

        assertThatIllegalStateException().isThrownBy(() -> policy.select(
                new AiExecutionContext(UUID.randomUUID(), false),
                new ModelRequirements(true, true)));
    }

    private static ProviderDescriptor provider(
            String id, boolean healthy, boolean toolCalling, boolean structuredOutput, boolean cloud) {
        return new ProviderDescriptor(id, new ProviderCapabilities(toolCalling, structuredOutput, true),
                healthy, cloud, id + "-v1");
    }
}
