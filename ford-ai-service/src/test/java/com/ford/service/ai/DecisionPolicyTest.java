package com.ford.service.ai;

import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyTest {

    private final DecisionPolicy policy = new DecisionPolicy();

    @Test
    void neverCreatesHandoffWhenTheFeatureIsDisabled() {
        assertThat(policy.mayCreateHandoff(decision(HandoffRecommendation.USER_REQUESTED), false))
                .isFalse();
        assertThat(policy.mayCreateHandoff(decision(HandoffRecommendation.NONE), true))
                .isFalse();
    }

    @Test
    void doesNotEscalateOnlyBecauseClassificationConfidenceIsLow() {
        assertThat(policy.mayCreateHandoff(decision(HandoffRecommendation.LOW_CONFIDENCE), true))
                .isFalse();
    }

    @Test
    void exposesOnlyAvailableToolsForTheClassifiedFamily() {
        var decision = decision(ToolFamily.FINANCE_ESTIMATE);

        assertThat(policy.allowedToolNames(decision,
                Set.of("finance_estimate", "vehicle_snapshot_search")))
                .containsExactly("finance_estimate");
        assertThat(policy.allowedToolNames(decision, Set.of("vehicle_snapshot_search")))
                .isEmpty();
    }

    @Test
    void exposesNoUnrelatedToolsWhenNoToolFamilyWasClassified() {
        var decision = decision(ToolFamily.NONE);

        assertThat(policy.allowedToolNames(decision,
                Set.of("search_vehicles", "list_promotions", "approved_knowledge_search")))
                .isEmpty();
        assertThat(policy.allowedToolNames(null, Set.of("search_vehicles", "list_promotions"))).isEmpty();
    }

    @Test
    void exposesOnlyMcpVehicleToolsForVehicleInformation() {
        var decision = decision(ToolFamily.VEHICLE_DETAILS);

        assertThat(policy.allowedToolNames(decision, Set.of(
                "search_vehicles", "get_vehicle", "compare_vehicles", "search_offers", "finance_estimate")))
                .containsExactlyInAnyOrder("search_vehicles", "get_vehicle", "compare_vehicles");
    }

    @Test
    void exposesEveryCatalogComparisonAndSourceToolForVehicleDetails() {
        var decision = decision(ToolFamily.VEHICLE_DETAILS);

        assertThat(policy.allowedToolNames(decision, Set.of(
                "search_vehicles", "get_vehicle", "compare_vehicles", "get_vehicle_provenance",
                "get_page_links", "compare_trims", "compare_model_years", "get_variant_comparisons",
                "vehicle_snapshot_search", "search_offers")))
                .containsExactlyInAnyOrder("search_vehicles", "get_vehicle", "compare_vehicles",
                        "get_vehicle_provenance", "get_page_links", "compare_trims",
                        "compare_model_years", "get_variant_comparisons", "vehicle_snapshot_search");
    }

    @Test
    void exposesCombinedVehicleAndOfferSearchForVehicleSearchRequests() {
        var decision = decision(ToolFamily.VEHICLE_SEARCH);

        assertThat(policy.allowedToolNames(decision, Set.of(
                "search_vehicles", "search_vehicle_and_offers", "search_offers")))
                .containsExactlyInAnyOrder("search_vehicles", "search_vehicle_and_offers");
    }

    @Test
    void exposesTheLocalVehicleSnapshotToolWhenItIsAvailable() {
        var decision = decision(ToolFamily.VEHICLE_SEARCH);

        assertThat(policy.allowedToolNames(decision, Set.of("vehicle_snapshot_search")))
                .containsExactly("vehicle_snapshot_search");
    }

    @Test
    void exposesOnlyMcpOfferToolsForOfferInformation() {
        var decision = decision(ToolFamily.OFFERS);

        assertThat(policy.allowedToolNames(decision, Set.of(
                "search_offers", "list_promotions", "get_catalog_status", "send_message")))
                .containsExactlyInAnyOrder("search_offers", "list_promotions", "get_catalog_status");
    }

    @Test
    void exposesCombinedVehicleAndOfferSearchForOfferRequests() {
        var decision = decision(ToolFamily.OFFERS);

        assertThat(policy.allowedToolNames(decision, Set.of(
                "search_offers", "search_vehicle_and_offers", "search_vehicles")))
                .containsExactlyInAnyOrder("search_offers", "search_vehicle_and_offers");
    }

    @Test
    void exposesCatalogAndKnowledgeToolsForVehicleTechnicalQuestions() {
        var decision = decision(ToolFamily.KNOWLEDGE_SEARCH);

        assertThat(policy.allowedToolNames(decision, Set.of(
                "approved_knowledge_search", "search_vehicles", "get_vehicle",
                "get_vehicle_provenance", "get_page_links", "vehicle_snapshot_search", "send_message")))
                .containsExactlyInAnyOrder("approved_knowledge_search", "search_vehicles", "get_vehicle",
                "get_vehicle_provenance", "get_page_links", "vehicle_snapshot_search");
    }

    @Test
    void keepsRulesFallbackClassificationWithinItsReadOnlyToolFamily() {
        var decision = new ConversationDecision(ConversationIntent.KNOWLEDGE, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.KNOWLEDGE_SEARCH,
                SensitiveDataLevel.NONE, List.of(new ClassificationEvidence(
                        ClassifierType.INTENT, "KNOWLEDGE", 1.0, "rules_intent",
                        "rules", "deterministic", 0, true)));

        assertThat(policy.allowedToolNames(decision, Set.of(
                "approved_knowledge_search", "search_vehicles", "get_vehicle",
                "list_promotions", "vehicle_snapshot_search")))
                .containsExactlyInAnyOrder("approved_knowledge_search", "search_vehicles",
                        "get_vehicle", "vehicle_snapshot_search");
    }

    @Test
    void keepsRulesClassifiedOfferRequestsInsideTheOfferToolFamily() {
        var decision = new ConversationDecision(ConversationIntent.OFFERS, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.OFFERS,
                SensitiveDataLevel.NONE, List.of(new ClassificationEvidence(
                        ClassifierType.INTENT, "OFFERS", 1.0, "rules_intent",
                        "rules", "deterministic", 0, true)));

        assertThat(policy.allowedToolNames(decision, Set.of(
                "search_offers", "list_promotions", "get_catalog_status", "search_vehicles")))
                .containsExactlyInAnyOrder("search_offers", "list_promotions", "get_catalog_status");
    }

    @Test
    void restrictsProviderForConfirmedSensitiveData() {
        assertThat(policy.shouldUseRestrictedProvider(decision(SensitiveDataLevel.CONFIRMED)))
                .isTrue();
        assertThat(policy.shouldUseRestrictedProvider(decision(SensitiveDataLevel.POSSIBLE)))
                .isFalse();
    }

    @Test
    void identifiesVolatileVehicleAndOfferInformation() {
        assertThat(policy.requiresVolatileInformation(decision(ToolFamily.VEHICLE_SEARCH))).isTrue();
        assertThat(policy.requiresVolatileInformation(decision(ToolFamily.VEHICLE_DETAILS))).isTrue();
        assertThat(policy.requiresVolatileInformation(decision(ToolFamily.OFFERS))).isTrue();
        assertThat(policy.requiresVolatileInformation(decision(ToolFamily.KNOWLEDGE_SEARCH))).isTrue();
        assertThat(policy.requiresVolatileInformation(decision(ToolFamily.FINANCE_ESTIMATE))).isFalse();
    }

    private static ConversationDecision decision(HandoffRecommendation recommendation) {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, recommendation, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());
    }

    private static ConversationDecision decision(ToolFamily family) {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, family, SensitiveDataLevel.NONE, List.of());
    }

    private static ConversationDecision decision(SensitiveDataLevel sensitiveData) {
        return new ConversationDecision(ConversationIntent.GENERAL, LeadTemperature.UNKNOWN,
                LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE, sensitiveData, List.of());
    }
}
