package com.ford.service.ai.adapter.out.typesafe;

import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationDecisionPort;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadNextAction;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.ai.configuration.ClassificationProperties;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TypeSafeConversationDecisionAdapter implements ConversationDecisionPort {

    private final TypeSafeClient client;
    private final ClassificationProperties properties;

    public TypeSafeConversationDecisionAdapter(TypeSafeClient client, ClassificationProperties properties) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public ConversationDecision decide(ClassificationContext context) {
        Objects.requireNonNull(context, "classification context is required");
        var started = System.nanoTime();
        var response = client.systemOne(state(context), questions());
        var latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return map(response, latencyMs, context.message());
    }

    private ConversationDecision map(SystemOneResponse response, long latencyMs, String message) {
        Objects.requireNonNull(response, "TypeSafe response is required");
        var intent = properties.intentEnabled()
                ? choice(response, "intent", ConversationIntent.class) : ConversationIntent.GENERAL;
        var intentConfidence = properties.intentEnabled() ? response.choice("intent").confidence() : 1.0;
        var leadStage = properties.leadEnabled()
                ? choice(response, "lead_stage", LeadStage.class) : LeadStage.NEW;
        var leadStageConfidence = properties.leadEnabled() ? response.choice("lead_stage").confidence() : 1.0;
        var temperature = properties.leadEnabled()
                ? score(response.score("lead_temperature"))
                : new ScoreValue(LeadTemperature.UNKNOWN, 1.0);
        var userRequested = properties.handoffEnabled()
                ? noul(response, "user_requests_human") : NoulValue.none();
        var negative = properties.handoffEnabled()
                ? noul(response, "negative_sentiment") : NoulValue.none();
        var complex = properties.handoffEnabled()
                ? noul(response, "complex_request") : NoulValue.none();
        var toolFamily = properties.toolsEnabled()
                ? choice(response, "tool_family", ToolFamily.class) : ToolFamily.NONE;
        var toolConfidence = properties.toolsEnabled() ? response.choice("tool_family").confidence() : 1.0;
        var sensitive = properties.sensitiveDataEnabled()
                ? noul(response, "has_sensitive_data") : NoulValue.none();
        var handoff = handoff(userRequested, negative, complex,
                intentConfidence, toolConfidence);
        var urgency = urgency(message, temperature.value(), intent);
        var nextAction = nextAction(intent, urgency, handoff.value());
        var model = response.model() == null || response.model().isBlank() ? "typesafe" : response.model();

        return new ConversationDecision(intent, temperature.value(), leadStage, handoff.value(),
                toolFamily, sensitive.level(), nextAction, urgency, summary(message), List.of(
                        evidence(ClassifierType.INTENT, intent.name(),
                                intentConfidence,
                                properties.intentEnabled() ? "model_choice" : "classifier_disabled", model, latencyMs,
                                !properties.intentEnabled()),
                        evidence(ClassifierType.LEAD, leadStage.name() + "/" + temperature.value().name(),
                                Math.min(leadStageConfidence, temperature.confidence()),
                                properties.leadEnabled() ? "model_choice_score" : "classifier_disabled",
                                model, latencyMs, !properties.leadEnabled()),
                        evidence(ClassifierType.HANDOFF, handoff.value().name(), handoff.confidence(),
                                properties.handoffEnabled() ? "noul_threshold" : "classifier_disabled",
                                model, latencyMs, !properties.handoffEnabled()),
                        evidence(ClassifierType.TOOL_FAMILY, toolFamily.name(),
                                toolConfidence,
                                properties.toolsEnabled() ? "model_choice" : "classifier_disabled",
                                model, latencyMs, !properties.toolsEnabled()),
                        evidence(ClassifierType.SENSITIVE_DATA, sensitive.level().name(), sensitive.confidence(),
                                properties.sensitiveDataEnabled() ? "noul_threshold" : "classifier_disabled",
                                model, latencyMs, !properties.sensitiveDataEnabled())));
    }

    private static LeadNextAction nextAction(ConversationIntent intent, boolean urgency,
                                             HandoffRecommendation handoff) {
        if (handoff == HandoffRecommendation.USER_REQUESTED) return LeadNextAction.CONNECT_ADVISOR;
        if (intent == ConversationIntent.APPOINTMENT || urgency && intent == ConversationIntent.VEHICLE_DETAILS) {
            return LeadNextAction.SCHEDULE_TEST_DRIVE;
        }
        if (intent == ConversationIntent.FINANCE) return LeadNextAction.SEND_QUOTE;
        if (intent != ConversationIntent.GENERAL) return LeadNextAction.FOLLOW_UP;
        return LeadNextAction.NO_ACTION;
    }

    private static boolean urgency(String message, LeadTemperature temperature, ConversationIntent intent) {
        var normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return temperature == LeadTemperature.HOT || intent == ConversationIntent.APPOINTMENT
                || normalized.contains("hoy") || normalized.contains("ahora")
                || normalized.contains("urgente") || normalized.contains("esta semana");
    }

    private static String summary(String message) {
        if (message == null || message.isBlank()) return "";
        var clean = message.trim().replaceAll("\\s+", " ");
        return clean.length() <= 240 ? clean : clean.substring(0, 240).trim();
    }

    private static String state(ClassificationContext context) {
        return "message=" + safe(context.message()) +
                "\nconversation_state=" + safe(context.conversationState()) +
                "\nmemory_context=" + safe(context.memoryContext());
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("[DECISION CONTEXT]", "[DECISION_CONTEXT]")
                .replace("[/DECISION CONTEXT]", "[/DECISION_CONTEXT]");
    }

    private Map<String, org.springaicommunity.typesafe.question.Question> questions() {
        var questions = new LinkedHashMap<String, org.springaicommunity.typesafe.question.Question>();
        if (properties.intentEnabled()) {
            questions.put("intent", choice("Clasifica la intención principal del cliente en una conversación de venta y atención de una agencia Ford mexicana.", Map.ofEntries(
                    Map.entry("GENERAL", "Saludo o conversación general sin petición específica."),
                    Map.entry("INVENTORY", "Disponibilidad o búsqueda de vehículos para comprar."),
                    Map.entry("VEHICLE_DETAILS", "Características, versiones o datos de un vehículo específico."),
                    Map.entry("FINANCE", "Financiamiento, crédito o mensualidades."),
                    Map.entry("OFFERS", "Promociones, descuentos u ofertas vigentes."),
                    Map.entry("APPOINTMENT", "Agendar o cambiar una cita o prueba de manejo."),
                    Map.entry("KNOWLEDGE", "Pregunta informativa sobre vehículos o procesos de compra."),
                    Map.entry("HUMAN_REQUEST", "Petición explícita para hablar con un asesor o una persona."),
                    Map.entry("TRADE_IN", "Valuación o intercambio de un vehículo usado."),
                    Map.entry("DEALERSHIP_INFO", "Ubicación, horarios o contacto de la agencia."),
                    Map.entry("SERVICE_SUPPORT", "Servicio, mantenimiento o reparación de un vehículo."),
                    Map.entry("COMPLAINT_STATUS", "Queja o seguimiento de una queja existente."),
                    Map.entry("OUT_OF_SCOPE", "Tema ajeno a vehículos Ford, la agencia o sus servicios."))));
        }
        if (properties.leadEnabled()) {
            questions.put("lead_stage", choice("Clasifica la etapa actual del proceso de compra, usando solo lo que el cliente expresó.", Map.of(
                    "NEW", "Primer contacto; todavía no hay señales suficientes de calificación.",
                    "QUALIFYING", "Está explorando opciones o compartiendo necesidades, presupuesto o plazo.",
                    "QUALIFIED", "Tiene intención, necesidades y próximos pasos claros para comprar.",
                    "CONVERTED", "Confirma que ya compró o completó la conversión.",
                    "LOST", "Declara que ya no seguirá con la compra o eligió otra alternativa.")));
            questions.put("lead_temperature", Score.of("Clasifica la temperatura comercial según intención y señales expresas de compra. UNKNOWN si no hay evidencia; COLD si la intención es débil; WARM si explora seriamente; HOT si expresa intención o plazo inmediato.",
                    "UNKNOWN", "COLD", "WARM", "HOT"));
        }
        if (properties.handoffEnabled()) {
            questions.put("user_requests_human", Noul.of("El usuario solicita explícitamente hablar con una persona."));
            questions.put("negative_sentiment", Noul.of("El mensaje expresa frustración, queja o sentimiento negativo."));
            questions.put("complex_request", Noul.of("La solicitud requiere coordinación o juicio humano complejo."));
        }
        if (properties.toolsEnabled()) {
            questions.put("tool_family", choice("Selecciona la familia de herramienta necesaria para responder con datos confiables. Usa la familia más específica y no elijas KNOWLEDGE_SEARCH si aplica una familia especializada: inventario usa VEHICLE_SEARCH, características de un modelo usan VEHICLE_DETAILS, pagos o crédito usan FINANCE_ESTIMATE, y promociones usan OFFERS. Elige NONE si no se necesita consultar datos externos.", Map.of(
                    "NONE", "No hace falta consultar una herramienta." ,
                    "VEHICLE_SEARCH", "Buscar unidades disponibles en el inventario." ,
                    "VEHICLE_DETAILS", "Consultar características de una unidad o modelo específico." ,
                    "OFFERS", "Consultar promociones u ofertas vigentes." ,
                    "FINANCE_ESTIMATE", "Calcular una estimación de pagos o financiamiento." ,
                    "KNOWLEDGE_SEARCH", "Solo para preguntas generales o información estática aprobada de la agencia; no usar cuando aplique una familia especializada." ,
                    "APPOINTMENT", "Agendar o actualizar una cita.")));
        }
        if (properties.sensitiveDataEnabled()) {
            questions.put("has_sensitive_data", Noul.of("El usuario incluye o solicita credenciales, secretos o datos financieros sensibles."));
        }
        return Map.copyOf(questions);
    }

    private static Choice choice(String instructions, Map<String, String> options) {
        return Choice.builder().instructions(instructions).options(options).build();
    }

    private static <E extends Enum<E>> E choice(SystemOneResponse response, String key, Class<E> type) {
        var value = response.choice(key).value();
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unknown TypeSafe label for " + key, exception);
        }
    }

    private static ScoreValue score(ScoreAnswer answer) {
        var label = answer.nearestLabel();
        try {
            return new ScoreValue(LeadTemperature.valueOf(label.trim().toUpperCase(Locale.ROOT)), answer.confidence());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unknown TypeSafe score label", exception);
        }
    }

    private static NoulValue noul(SystemOneResponse response, String key) {
        var value = response.noulValue(key);
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Invalid TypeSafe Noul value for " + key);
        }
        var level = value >= 0.5 ? SensitiveDataLevel.CONFIRMED : SensitiveDataLevel.NONE;
        return new NoulValue(value, Math.max(value, 1.0 - value), level);
    }

    private HandoffValue handoff(NoulValue userRequested, NoulValue negative, NoulValue complex,
                                 double intentConfidence, double toolConfidence) {
        if (userRequested.value() >= 0.5) {
            return new HandoffValue(HandoffRecommendation.USER_REQUESTED, userRequested.confidence());
        }
        if (negative.value() >= 0.5) {
            return new HandoffValue(HandoffRecommendation.NEGATIVE_SENTIMENT, negative.confidence());
        }
        if (complex.value() >= 0.5) {
            return new HandoffValue(HandoffRecommendation.COMPLEX_REQUEST, complex.confidence());
        }
        var lowConfidence = intentConfidence < properties.minConfidence()
                || toolConfidence < properties.minConfidence();
        return new HandoffValue(lowConfidence ? HandoffRecommendation.LOW_CONFIDENCE : HandoffRecommendation.NONE,
                lowConfidence ? Math.min(intentConfidence, toolConfidence) :
                        Math.min(Math.min(userRequested.confidence(), negative.confidence()), complex.confidence()));
    }

    private static ClassificationEvidence evidence(ClassifierType classifier, String classification, double confidence,
                                                   String reason, String model, long latencyMs, boolean fallbackUsed) {
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Invalid TypeSafe confidence for " + classifier);
        }
        return new ClassificationEvidence(classifier, classification, confidence, reason,
                "typesafe", model, latencyMs, fallbackUsed);
    }

    private record ScoreValue(LeadTemperature value, double confidence) { }
    private record NoulValue(double value, double confidence, SensitiveDataLevel level) {
        private static NoulValue none() {
            return new NoulValue(0.0, 1.0, SensitiveDataLevel.NONE);
        }
    }
    private record HandoffValue(HandoffRecommendation value, double confidence) { }
}
