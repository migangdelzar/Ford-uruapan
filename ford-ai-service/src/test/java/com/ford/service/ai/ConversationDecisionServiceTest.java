package com.ford.service.ai;

import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.IntentRouter;
import com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationDecisionServiceTest {

    @Test
    void usesDeterministicRulesInsteadOfSendingSensitiveTextToPrimaryClassifier() {
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            throw new AssertionError("Sensitive text must not reach the primary classifier");
        };
        ConversationDecisionPort rules = context -> new ConversationDecision(
                ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.CONFIRMED, List.of());
        var context = new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), "masked input", null,
                "BOT_ACTIVE", true);

        var decision = new ConversationDecisionService(primary, rules, new ClassificationProperties()).decide(context);

        assertThat(primaryCalled[0]).isFalse();
        assertThat(decision.sensitiveDataLevel()).isEqualTo(SensitiveDataLevel.CONFIRMED);
    }

    @Test
    void skipsPrimaryClassifierForAnExplicitFordFactRequest() {
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            return new ConversationDecision(ConversationIntent.GENERAL,
                    LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                    ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());
        };
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(primary, rules, new ClassificationProperties());

        var decision = service.decide(context("¿Qué precio tiene Ford Ranger 2027?"));

        assertThat(primaryCalled[0]).isFalse();
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
        assertThat(decision.vehicleEntities().model()).contains("Ranger");
        assertThat(decision.vehicleEntities().modelYear()).contains(2027);
        assertThat(decision.toPromptContext()).contains("vehicle_model=ranger", "vehicle_model_year=2027",
                "vehicle_model_source=current_message");
    }

    @Test
    void routesCatalogFactQuestionAboutPreviouslyNamedVersionsToVehicleTools() {
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            return new ConversationDecision(ConversationIntent.KNOWLEDGE, LeadTemperature.UNKNOWN, LeadStage.NEW,
                    HandoffRecommendation.NONE, ToolFamily.KNOWLEDGE_SEARCH, SensitiveDataLevel.NONE, List.of());
        };
        var memory = "Historial reciente:\n- usuario: Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2\n"
                + "- asistente: Ranger 2027 XL y XLT son las versiones comparadas.";
        var service = new ConversationDecisionService(primary, new RulesConversationDecisionAdapter(new IntentRouter()),
                new ClassificationProperties());

        var decision = service.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "¿La fuente del catálogo trae dimensiones para esas versiones?", memory, "BOT_ACTIVE", false));

        assertThat(primaryCalled[0]).isFalse();
        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void resolvesAccentedSingularVersionFollowUpFromTheLatestAssistantVehicleReference() {
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            return ConversationDecision.deterministicDefault();
        };
        var memory = "Historial reciente:\n"
                + "- usuario: Sí, me refiero a la Ranger XLT Gasolina 4x2 2027.\n"
                + "- asistente: La Ranger XLT Gasolina 4x2 2027 tiene bolsas de aire y cámara de reversa.";
        var service = new ConversationDecisionService(primary, new RulesConversationDecisionAdapter(new IntentRouter()),
                new ClassificationProperties());

        var decision = service.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Entonces contéstame solo con lo que la ficha de esa versión confirma sobre seguridad.",
                memory, "BOT_ACTIVE", false));

        assertThat(primaryCalled[0]).isFalse();
        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
        assertThat(decision.vehicleEntities().model()).contains("Ranger");
        assertThat(decision.vehicleEntities().modelYear()).contains(2027);
        assertThat(decision.vehicleEntities().trim()).contains("XLT Gasolina 4x2");
        assertThat(decision.vehicleEntities().modelYearSource())
                .isEqualTo(com.ford.service.ai.application.VehicleEntityResolution.Source.MEMORY_REFERENCE);
        assertThat(decision.vehicleEntities().trimSource())
                .isEqualTo(com.ford.service.ai.application.VehicleEntityResolution.Source.MEMORY_REFERENCE);
    }

    @Test
    void keepsConfirmationAboutThePriorAssistantVersionReferenceOutOfCatalogTools() {
        var memory = "Historial reciente:\n"
                + "- usuario: ¿La seguridad corresponde a la misma XLT 2027?\n"
                + "- asistente: Para revisar la versión exacta de Ranger 2027, ¿cuál busca: "
                + "XLT Diésel 4x4 o XLT Gasolina 4x2?";
        var service = new ConversationDecisionService(context -> ConversationDecision.deterministicDefault(),
                new RulesConversationDecisionAdapter(new IntentRouter()), new ClassificationProperties());

        var decision = service.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Cuando dices esa versión, ¿sigues hablando de la Ranger XLT?", memory, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
    }

    @Test
    void keepsARealCatalogQuestionWhenConfirmationAlsoAsksForVehicleFacts() {
        var memory = "Historial reciente:\n"
                + "- asistente: Para revisar la versión exacta de Ranger 2027, ¿cuál busca: "
                + "XLT Diésel 4x4 o XLT Gasolina 4x2?";
        var service = new ConversationDecisionService(context -> ConversationDecision.deterministicDefault(),
                new RulesConversationDecisionAdapter(new IntentRouter()), new ClassificationProperties());

        var decision = service.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Cuando dices esa versión, ¿sigues hablando de la Ranger XLT? ¿Cuál es su precio?",
                memory, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void resolvesManualOnlyAgainstTheLatestExplicitTransmissionOffer() {
        var memory = "[HISTORIAL DE CONVERSACIÓN]\nHistorial reciente:\n"
                + "- usuario: Busco Mustang GT Convertible 2026\n"
                + "- asistente: ¿Le interesa la versión automática o la manual?";
        var decision = new ConversationDecisionService(context -> ConversationDecision.deterministicDefault(),
                new RulesConversationDecisionAdapter(new IntentRouter()), new ClassificationProperties())
                .decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), "Manual", memory,
                        "BOT_ACTIVE", false));

        assertThat(decision.vehicleEntities().model()).contains("Mustang");
        assertThat(decision.vehicleEntities().modelYear()).contains(2026);
        assertThat(decision.vehicleEntities().trim()).contains("GT Convertible");
        assertThat(decision.summary()).contains("manual");
    }

    @Test
    void doesNotResolveShortOptionWhenLatestOfferIsAmbiguous() {
        var memory = "Historial reciente:\n- usuario: Busco Mustang GT Convertible 2026\n"
                + "- asistente: ¿Prefiere manual? También tenemos opción manual.";
        var decision = new ConversationDecisionService(context -> ConversationDecision.deterministicDefault(),
                new RulesConversationDecisionAdapter(new IntentRouter()), new ClassificationProperties())
                .decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), "Manual", memory,
                        "BOT_ACTIVE", false));

        assertThat(decision.vehicleEntities().model()).isEmpty();
    }

    @Test
    void doesNotLeakOlderModelOrYearIntoTheActiveMustangExchange() {
        var memory = "Historial reciente:\n- usuario: Busco Ranger 2024\n"
                + "- asistente: ¿Busca precio o disponibilidad?\n- usuario: También me interesa un Mustang 2026\n"
                + "- asistente: ¿Le interesa la versión automática o la manual?";
        var decision = decisionFor("Manual", memory);

        assertThat(decision.vehicleEntities().model()).contains("Mustang");
        assertThat(decision.vehicleEntities().modelYear()).contains(2026);
    }

    @Test
    void leavesActiveExchangeUnresolvedWhenItContainsMultipleCandidateYears() {
        var memory = "Historial reciente:\n- usuario: Mustang 2025 o Mustang 2026\n"
                + "- asistente: ¿Le interesa la versión automática o la manual?";

        assertThat(decisionFor("Manual", memory).vehicleEntities().modelYear()).isEmpty();
    }

    @Test
    void resolvesExplicitGtConvertibleOptionOnlyWithUniqueMustangAndYear() {
        var memory = "Historial reciente:\n- usuario: Busco Mustang 2026\n"
                + "- asistente: ¿Le interesa el GT Convertible o el EcoBoost?";

        var entities = decisionFor("GT Convertible", memory).vehicleEntities();

        assertThat(entities.model()).contains("Mustang");
        assertThat(entities.modelYear()).contains(2026);
        assertThat(entities.trim()).contains("GT Convertible");
    }

    private static ConversationDecision decisionFor(String message, String memory) {
        return new ConversationDecisionService(context -> ConversationDecision.deterministicDefault(),
                new RulesConversationDecisionAdapter(new IntentRouter()), new ClassificationProperties())
                .decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), message, memory,
                        "BOT_ACTIVE", false));
    }

    @Test
    void restoresVolatileToolRouteWhenPrimaryClassifierMissesAnOffersQuestion() {
        var primaryDecision = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("Quiero conocer qué modelos tienen promoción"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.OFFERS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.OFFERS);
        assertThat(decision.leadTemperature()).isEqualTo(LeadTemperature.WARM);
        assertThat(decision.leadStage()).isEqualTo(LeadStage.QUALIFYING);
        assertThat(new DecisionPolicy().allowedToolNames(decision, Set.of("list_promotions", "search_offers")))
                .contains("list_promotions", "search_offers");
    }

    @Test
    void routesNeedsBasedFordRecommendationToRequiredVehicleSearchWhenPrimarySaysGeneral() {
        var primaryDecision = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());
        var memory = "Historial reciente:\n"
                + "- usuario: La usaría para ir al trabajo y salir con mi familia.\n"
                + "- usuario: Me interesa una pickup fácil de estacionar en ciudad.\n"
                + "- asistente: ¿Qué prioridad es más importante?";

        var decision = service.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "¿Qué Ford me convendría revisar primero con ese uso mixto?", memory, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.INVENTORY);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_SEARCH);
        var policy = new DecisionPolicy();
        assertThat(policy.requiresVolatileInformation(decision)).isTrue();
        assertThat(policy.allowedToolNames(decision,
                Set.of("search_vehicles", "search_vehicle_and_offers", "search_offers", "finance_estimate")))
                .contains("search_vehicles")
                .doesNotContain("search_offers", "finance_estimate");
    }

    @Test
    void keepsGeneralPrePurchaseDimensionsGuidanceOutOfCatalogToolsDespiteFordMemory() {
        var primaryDecision = new ConversationDecision(ConversationIntent.KNOWLEDGE,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.KNOWLEDGE_SEARCH, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());
        var memory = "Historial reciente: comparó Ford Ranger XLT y Ranger XL.";

        var decision = service.decide(new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Para mi familia, ¿qué dimensiones deberíamos revisar antes de decidir?",
                memory, "BOT_ACTIVE", false));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
    }

    @Test
    void replacesAConflictingSpecializedToolRouteWithTheDeterministicRoute() {
        var primaryDecision = new ConversationDecision(ConversationIntent.FINANCE,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.KNOWLEDGE_SEARCH, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("Quiero una mensualidad"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.FINANCE);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.FINANCE_ESTIMATE);
    }

    @Test
    void preventsPrimaryToolCallsForDeterministicallyGeneralOrOutOfScopeMessages() {
        var primaryDecision = new ConversationDecision(ConversationIntent.OUT_OF_SCOPE,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.KNOWLEDGE_SEARCH, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("Escribe un poema"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.OUT_OF_SCOPE);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
    }

    @Test
    void keepsCourtesyTurnOutOfVehicleToolsEvenWhenMessageAndMemoryMentionModels() {
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            return new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                    LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                    ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, List.of());
        };
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var memory = "Historial reciente:\n- usuario: Busco Ford Ranger\n- asistente: ¿Qué versión le interesa?";
        var context = new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Gracias, justo eso quería explicar antes de ver modelos.", memory, "BOT_ACTIVE", false);

        var decision = new ConversationDecisionService(primary, rules, new ClassificationProperties()).decide(context);

        assertThat(primaryCalled[0]).isFalse();
        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.NONE);
        assertThat(decision.vehicleEntities().model()).isEmpty();
    }

    @Test
    void keepsMixedCourtesyAndVehicleFactQuestionOnTheVehicleToolRoute() {
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var classificationContext = new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(),
                "Gracias, ¿qué motor tiene la Ranger 2027?", null, "BOT_ACTIVE", false);

        var decision = new ConversationDecisionService(ignored -> ConversationDecision.deterministicDefault(),
                rules, new ClassificationProperties()).decide(classificationContext);

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void keepsTheMoreSpecificPrimaryVehicleRouteWhenRulesOnlyHaveAKnowledgeRoute() {
        var primaryDecision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var service = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties());

        var decision = service.decide(context("¿Qué rendimiento de gasolina tiene la Ford Escape? "));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void keepsJeVVehicleToolForShortTrimFollowUpWhenMemoryEstablishesFordContext() {
        var primaryDecision = new ConversationDecision(ConversationIntent.VEHICLE_DETAILS,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.VEHICLE_DETAILS, SensitiveDataLevel.NONE, List.of());
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var classificationContext = new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), "La XLT",
                "Historial reciente: ¿Qué promociones tienen para Ranger?", "BOT_ACTIVE", false);

        var decision = new ConversationDecisionService(context -> primaryDecision, rules,
                new ClassificationProperties()).decide(classificationContext);

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
    }

    @Test
    void recordsRulesFallbackWhenThePrimaryClassifierFails() {
        ConversationDecisionPort primary = context -> { throw new IllegalStateException("provider unavailable"); };
        ConversationDecision fallback = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE, ToolFamily.NONE,
                SensitiveDataLevel.NONE, List.of());
        ConversationDecisionPort rules = context -> fallback;
        var properties = new ClassificationProperties();
        var service = new ConversationDecisionService(primary, rules, properties);

        var decision = service.decide(context("Hola"));

        assertThat(decision).isSameAs(fallback);
    }

    @Test
    void usesRulesWhenClassificationIsDisabled() {
        var properties = new ClassificationProperties();
        properties.setEnabled(false);
        var primaryCalled = new boolean[1];
        ConversationDecisionPort primary = context -> {
            primaryCalled[0] = true;
            throw new AssertionError("primary must not be called");
        };
        ConversationDecisionPort rules = context -> new ConversationDecision(
                ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());

        new ConversationDecisionService(primary, rules, properties).decide(context("Hola"));

        assertThat(primaryCalled[0]).isFalse();
    }

    @Test
    void usesRulesWhenATypeSafeClassifierReturnsLowConfidenceEvidence() {
        var properties = new ClassificationProperties();
        properties.setMinConfidence(0.8);
        var lowConfidence = new ConversationDecision(ConversationIntent.FINANCE,
                LeadTemperature.WARM, LeadStage.QUALIFYING, HandoffRecommendation.NONE,
                ToolFamily.FINANCE_ESTIMATE, SensitiveDataLevel.NONE,
                List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "FINANCE", 0.4,
                        "model_choice", "typesafe", "test", 1, false)));
        ConversationDecisionPort rules = context -> new ConversationDecision(
                ConversationIntent.GENERAL, LeadTemperature.UNKNOWN, LeadStage.NEW,
                HandoffRecommendation.NONE, ToolFamily.NONE, SensitiveDataLevel.NONE, List.of());

        var decision = new ConversationDecisionService(context -> lowConfidence, rules, properties)
                .decide(context("mensualidad"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.GENERAL);
    }

    @Test
    void routesTerritoryToVehicleDetailsWhenThePrimaryClassifierFallsBackOnLowConfidence() {
        var properties = new ClassificationProperties();
        properties.setMinConfidence(0.8);
        var lowConfidence = new ConversationDecision(ConversationIntent.GENERAL,
                LeadTemperature.UNKNOWN, LeadStage.NEW, HandoffRecommendation.NONE,
                ToolFamily.NONE, SensitiveDataLevel.NONE,
                List.of(new com.ford.service.ai.application.ClassificationEvidence(
                        com.ford.service.ai.application.ClassifierType.INTENT, "GENERAL", 0.4,
                        "model_choice", "typesafe", "test", 1, false)));
        var rules = new RulesConversationDecisionAdapter(new IntentRouter());
        var decision = new ConversationDecisionService(context -> lowConfidence, rules, properties)
                .decide(context("Territory"));

        assertThat(decision.intent()).isEqualTo(ConversationIntent.VEHICLE_DETAILS);
        assertThat(decision.toolFamily()).isEqualTo(ToolFamily.VEHICLE_DETAILS);
        assertThat(new DecisionPolicy().allowedToolNames(decision, Set.of("get_vehicle", "search_vehicles")))
                .contains("get_vehicle", "search_vehicles");
    }

    private static ClassificationContext context(String message) {
        return new ClassificationContext(UUID.randomUUID(), UUID.randomUUID(), message,
                null, "BOT_ACTIVE", false);
    }
}
