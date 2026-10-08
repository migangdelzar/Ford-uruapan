package com.ford.service.ai;

import com.ford.service.ai.adapter.out.typesafe.TypeSafeConversationDecisionAdapter;
import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.Usage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TypeSafeConversationDecisionAdapterTest {

    @Test
    void mapsOneStructuredResponseToTheTypedDecisionBundle() {
        var client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response());
        var properties = new ClassificationProperties();
        var adapter = new TypeSafeConversationDecisionAdapter(client, properties);

        var decision = adapter.decide(new ClassificationContext(
                UUID.randomUUID(), UUID.randomUUID(), "Quiero una mensualidad", null, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.FINANCE);
        assertThat(decision.leadStage()).isEqualTo(LeadStage.QUALIFYING);
        assertThat(decision.leadTemperature()).isEqualTo(LeadTemperature.WARM);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.FINANCE_ESTIMATE);
        assertThat(decision.evidence()).hasSize(5);
        assertThat(decision.evidence()).extracting(ClassificationEvidence::classifier)
                .containsExactly(ClassifierType.INTENT, ClassifierType.LEAD, ClassifierType.HANDOFF,
                        ClassifierType.TOOL_FAMILY, ClassifierType.SENSITIVE_DATA);
        assertThat(decision.evidence()).allMatch(evidence -> evidence.provider().equals("typesafe"));
        verify(client).systemOne(anyString(), anyMap());
    }

    @Test
    void usesConfiguredConfidenceThresholdAndClassifierToggles() {
        var client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response());
        var properties = new ClassificationProperties();
        properties.setMinConfidence(0.95);
        properties.setIntentEnabled(false);
        properties.setToolsEnabled(false);
        var adapter = new TypeSafeConversationDecisionAdapter(client, properties);

        var decision = adapter.decide(new ClassificationContext(
                UUID.randomUUID(), UUID.randomUUID(), "Quiero una mensualidad", null, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(decision.toolFamily()).isEqualTo(com.ford.service.ai.application.ToolFamily.NONE);
        assertThat(decision.handoffRecommendation()).isEqualTo(HandoffRecommendation.NONE);
        assertThat(decision.evidence()).filteredOn(evidence ->
                        evidence.classifier() == ClassifierType.INTENT
                                || evidence.classifier() == ClassifierType.TOOL_FAMILY)
                .allMatch(ClassificationEvidence::fallbackUsed);
    }

    @Test
    void doesNotAskJevToClassifySensitiveDataWhenThatQuestionIsDisabled() {
        var client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response());
        var properties = new ClassificationProperties();
        properties.setSensitiveDataEnabled(false);
        properties.setHandoffEnabled(true);
        var adapter = new TypeSafeConversationDecisionAdapter(client, properties);

        adapter.decide(new ClassificationContext(
                UUID.randomUUID(), UUID.randomUUID(), "Me interesa una Ranger", null, "BOT_ACTIVE", false));

        var questions = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).systemOne(anyString(), questions.capture());
        assertThat(questions.getValue()).containsKeys("intent", "lead_stage", "lead_temperature", "tool_family",
                "user_requests_human", "negative_sentiment", "complex_request")
                .doesNotContainKey("has_sensitive_data");
    }

    @Test
    void classifiesTradeInAsARequestCategoryWithoutAssigningAnUnregisteredTool() {
        var client = mock(TypeSafeClient.class);
        when(client.systemOne(anyString(), anyMap())).thenReturn(response("TRADE_IN", "NONE"));
        var adapter = new TypeSafeConversationDecisionAdapter(client, new ClassificationProperties());

        var decision = adapter.decide(new ClassificationContext(
                UUID.randomUUID(), UUID.randomUUID(), "Quiero dar mi auto a cuenta", null, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.TRADE_IN);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
        assertThat(decision.leadTemperature()).isEqualTo(LeadTemperature.WARM);
    }

    private static SystemOneResponse response() {
        return response("FINANCE", "FINANCE_ESTIMATE");
    }

    private static SystemOneResponse response(String intent, String toolFamily) {
        var answers = new HashMap<String, org.springaicommunity.typesafe.response.Answer>();
        answers.put("intent", new ChoiceAnswer(intent, Map.of(intent, 0.91), 0.91));
        answers.put("lead_stage", new ChoiceAnswer("QUALIFYING", Map.of("QUALIFYING", 0.84), 0.84));
        answers.put("lead_temperature", new ScoreAnswer(2,
                Map.of(0, JsonContent.of("UNKNOWN"), 1, JsonContent.of("COLD"),
                        2, JsonContent.of("WARM"), 3, JsonContent.of("HOT")),
                Map.of(2, 0.79), 0.79));
        answers.put("user_requests_human", new NoulAnswer(0.04));
        answers.put("negative_sentiment", new NoulAnswer(0.03));
        answers.put("complex_request", new NoulAnswer(0.12));
        answers.put("tool_family", new ChoiceAnswer(toolFamily, Map.of(toolFamily, 0.88), 0.88));
        answers.put("has_sensitive_data", new NoulAnswer(0.02));
        return new SystemOneResponse("jev", answers, Usage.EMPTY);
    }
}
