package com.ford.service.ai;

import com.ford.service.ai.application.DefaultModelProviderPolicy;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ModelRequirements;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.configuration.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ModelProviderPolicyTest {

    @Test
    void prefersCloudProviderForNonSensitiveStructuredToolRequest() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("openai", true, true, true, true),
                provider("ollama", true, true, true, false)));

        var selected = policy.select(
                new AiExecutionContext(UUID.randomUUID(), false),
                new ModelRequirements(true, true));

        assertThat(selected.id()).isEqualTo("openai");
    }

    @Test
    void returnsEligibleProvidersInConfiguredOrderForNonSensitiveRequests() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("openai", true, true, true, true),
                provider("ollama", true, true, true, false),
                provider("unhealthy", false, true, true, true),
                provider("no-tools", true, false, true, true),
                provider("no-structured-output", true, true, false, true)));

        var candidates = policy.candidates(new AiExecutionContext(UUID.randomUUID(), false),
                new ModelRequirements(true, true));

        assertThat(candidates).extracting(ProviderDescriptor::id).containsExactly("openai", "ollama");
    }

    @Test
    void excludesEveryCloudCandidateForConfirmedSensitiveRequests() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("openai", true, true, true, true),
                provider("ollama", true, true, true, false),
                provider("second-cloud", true, true, true, true)));
        var decision = new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.CONFIRMED, List.of());

        var candidates = policy.candidates(new AiExecutionContext(
                        UUID.randomUUID(), false, UUID.randomUUID(), null, decision),
                new ModelRequirements(true, true));

        assertThat(candidates).extracting(ProviderDescriptor::id).containsExactly("ollama");
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
    void selectsLocalProviderWhenClassifierConfirmsSensitiveData() {
        ModelProviderPolicy policy = new DefaultModelProviderPolicy(List.of(
                provider("openai", true, true, true, true),
                provider("ollama", true, true, true, false)));
        var decision = new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.CONFIRMED, List.of());

        var selected = policy.select(
                new AiExecutionContext(UUID.randomUUID(), false, UUID.randomUUID(), null, decision),
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

    @Test
    void selectsOnlyProvidersWithTheRequestedImageCapability() {
        var imageCapable = new ProviderDescriptor("image-provider",
                new ProviderCapabilities(true, true, true, true, false), true, true, "vision-v1");
        var textOnly = provider("text-only", true, true, true, true);
        var policy = new DefaultModelProviderPolicy(List.of(textOnly, imageCapable));

        var candidates = policy.candidates(new AiExecutionContext(UUID.randomUUID(), false),
                new ModelRequirements(false, false, true, false));

        assertThat(candidates).extracting(ProviderDescriptor::id).containsExactly("image-provider");
    }

    @Test
    void selectsOnlyProvidersWithTheRequestedAudioCapability() {
        var audioCapable = new ProviderDescriptor("audio-provider",
                new ProviderCapabilities(true, true, true, false, true), true, false, "audio-v1");
        var imageOnly = new ProviderDescriptor("image-only",
                new ProviderCapabilities(true, true, true, true, false), true, false, "image-v1");
        var policy = new DefaultModelProviderPolicy(List.of(imageOnly, audioCapable));

        var candidates = policy.candidates(new AiExecutionContext(UUID.randomUUID(), false),
                new ModelRequirements(false, false, false, true));

        assertThat(candidates).extracting(ProviderDescriptor::id).containsExactly("audio-provider");
    }

    @Test
    void modalityCapabilityDoesNotAllowCloudProcessingOfSensitiveMessages() {
        var cloudVision = new ProviderDescriptor("cloud-vision",
                new ProviderCapabilities(true, true, true, true, true), true, true, "vision-v1");
        var localTextOnly = provider("local", true, true, true, false);
        var policy = new DefaultModelProviderPolicy(List.of(cloudVision, localTextOnly));

        var candidates = policy.candidates(new AiExecutionContext(UUID.randomUUID(), true),
                new ModelRequirements(false, false, true, false));

        assertThat(candidates).isEmpty();
    }

    @Test
    void mediaMustBeEnabledAndProviderMustBeApprovedForTheRequestedModality() {
        var properties = new AiProperties();

        assertThat(properties.allowsMediaProvider("openai", true, false)).isFalse();
        properties.setMediaEnabled(true);
        properties.setMediaApprovedProviders(List.of("openai"));
        properties.setMediaImageProviders(List.of("openai"));

        assertThat(properties.allowsMediaProvider("openai", true, false)).isTrue();
        assertThat(properties.allowsMediaProvider("openai", false, true)).isFalse();
        assertThat(properties.allowsMediaProvider("ollama", true, false)).isFalse();
    }

    private static ProviderDescriptor provider(
            String id, boolean healthy, boolean toolCalling, boolean structuredOutput, boolean cloud) {
        return new ProviderDescriptor(id, new ProviderCapabilities(toolCalling, structuredOutput, true),
                healthy, cloud, id + "-v1");
    }
}
