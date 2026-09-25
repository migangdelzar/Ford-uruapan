package com.ford.service.ai.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Separates commercial lead qualification from human escalation.
 *
 * <p>The current message and user messages in hot memory are treated as a
 * single signal window. This keeps the policy deterministic while allowing a
 * customer to express intent over several turns.</p>
 */
public final class LeadQualificationPolicy {

    private static final Pattern CONTACT_DETAILS = Pattern.compile(
            "\\b\\d{10}\\b|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Set<String> VEHICLE_TERMS = Set.of(
            "ford", "ranger", "bronco", "maverick", "territory", "explorer", "mustang", "f-150",
            "modelo", "vehiculo", "auto", "coche", "camioneta");
    private static final Set<String> COMMERCIAL_TERMS = Set.of(
            "precio", "oferta", "promocion", "bono", "disponible", "inventario", "stock");
    private static final Set<String> FINANCING_TERMS = Set.of(
            "financiamiento", "financiacion", "financiar", "credito", "enganche", "mensualidad", "contado");
    private static final Set<String> TIMING_TERMS = Set.of(
            "este mes", "esta semana", "hoy", "pronto", "inmediatamente", "ya quiero");
    private static final Set<String> HUMAN_TERMS = Set.of(
            "asesor", "asesora", "humano", "persona", "que me llame", "que me llamen");
    private static final Set<String> ACTION_VERBS = Set.of(
            "quiero", "quisiera", "me interesa", "me gustaria", "necesito", "deseo", "puedo",
            "agendar", "reservar");
    private static final Set<String> PURCHASE_ACTION_TERMS = Set.of(
            "comprar", "cotizacion", "cotizacion formal", "cotizar", "apartarlo", "auto a cuenta",
            "toma a cuenta", "trade in");
    private static final Set<String> APPOINTMENT_ACTION_TERMS = Set.of(
            "agendar", "reservar", "quiero una cita", "necesito una cita", "me gustaria una cita");

    public Assessment assess(String message, String memoryContext, ConversationDecision modelDecision) {
        Objects.requireNonNull(modelDecision, "model decision is required");
        var messages = userMessages(message, memoryContext);
        var allText = normalize(String.join("\n", messages));
        var signals = new LinkedHashSet<String>();
        var score = 0;

        if (containsAny(allText, VEHICLE_TERMS)) {
            signals.add("vehicle_interest");
            score++;
        }
        if (containsAny(allText, COMMERCIAL_TERMS)) {
            signals.add("commercial_interest");
            score++;
        }
        if (containsAny(allText, FINANCING_TERMS)) {
            signals.add("financing");
            score++;
        }
        if (containsAny(allText, TIMING_TERMS)) {
            signals.add("near_term");
            score += 2;
        }
        if (CONTACT_DETAILS.matcher(allText).find()) {
            signals.add("contact_details");
            score += 2;
        }
        var explicitHuman = containsAny(allText, HUMAN_TERMS);
        if (explicitHuman) {
            signals.add("human_request");
            score += 3;
        }
        var explicitAction = explicitAction(allText);
        if (explicitAction) {
            signals.add("explicit_action");
            score += 3;
        }

        var stage = stage(modelDecision.leadStage(), score);
        var temperature = temperature(modelDecision.leadTemperature(), score, signals);
        return new Assessment(score, stage, temperature, explicitHuman || explicitAction,
                List.copyOf(signals));
    }

    public ConversationDecision enrich(ConversationDecision decision, Assessment assessment) {
        Objects.requireNonNull(decision, "decision is required");
        Objects.requireNonNull(assessment, "assessment is required");
        if (decision.leadStage() == assessment.stage()
                && decision.leadTemperature() == assessment.temperature()) {
            return decision;
        }
        var confidence = Math.min(1.0, assessment.score() / 8.0);
        var evidence = new ClassificationEvidence(ClassifierType.LEAD,
                assessment.stage().name() + "/" + assessment.temperature().name(), confidence,
                "deterministic_lead_signal_score", "rules", "lead-policy", 0, true);
        return decision.withLeadClassification(assessment.stage(), assessment.temperature(), evidence);
    }

    private static List<String> userMessages(String message, String memoryContext) {
        var messages = new ArrayList<String>();
        if (message != null && !message.isBlank()) {
            messages.add(message);
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            for (var line : memoryContext.split("\\R")) {
                var marker = "- usuario:";
                var normalizedLine = line.toLowerCase(Locale.ROOT);
                var index = normalizedLine.indexOf(marker);
                if (index >= 0) {
                    messages.add(line.substring(index + marker.length()));
                }
            }
        }
        return messages;
    }

    private static boolean explicitAction(String text) {
        if (text.contains("cotizacion formal")) {
            return true;
        }
        if (containsAny(text, ACTION_VERBS) && containsAny(text, PURCHASE_ACTION_TERMS)) {
            return true;
        }
        return text.contains("prueba de manejo")
                && containsAny(text, APPOINTMENT_ACTION_TERMS);
    }

    private static LeadStage stage(LeadStage existing, int score) {
        if (existing == LeadStage.CONVERTED || existing == LeadStage.LOST) {
            return existing;
        }
        if (score >= 3) {
            return LeadStage.QUALIFIED;
        }
        if (score > 0) {
            return LeadStage.QUALIFYING;
        }
        return existing;
    }

    private static LeadTemperature temperature(LeadTemperature existing, int score, Set<String> signals) {
        if (existing == LeadTemperature.HOT) {
            return existing;
        }
        if (score >= 6 || signals.contains("explicit_action") || signals.contains("human_request")) {
            return LeadTemperature.HOT;
        }
        if (score >= 3 || existing == LeadTemperature.WARM) {
            return LeadTemperature.WARM;
        }
        if (score > 0 && existing == LeadTemperature.UNKNOWN) {
            return LeadTemperature.COLD;
        }
        return existing;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, Set<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    public record Assessment(int score, LeadStage stage, LeadTemperature temperature,
                             boolean shouldEscalate, List<String> signals) {
        public Assessment {
            if (score < 0) {
                throw new IllegalArgumentException("lead score must not be negative");
            }
            Objects.requireNonNull(stage, "lead stage is required");
            Objects.requireNonNull(temperature, "lead temperature is required");
            signals = List.copyOf(signals == null ? List.of() : signals);
        }
    }
}
