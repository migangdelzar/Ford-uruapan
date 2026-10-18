package com.ford.service.conversations.application;

import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.conversations.api.SpecialistHandoffPayload;
import com.ford.service.catalog.api.VehicleModelNames;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneySnapshot;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds the handoff payload only from persisted customer turns and the completed information answer. */
public final class SpecialistHandoffPayloadFactory {
    private static final Pattern NEED = Pattern.compile(
            "(?i)\\b(?:busco|buscando|necesito|me interesa|prefiero|para)\\s+[^.!?]{3,100}");
    private static final Pattern QUESTION = Pattern.compile("[^.!?]*\\?+");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\+?\\d[\\d ()-]{8,}\\d(?!\\d)");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{7,}");
    private static final Set<String> NON_FINANCING = Set.of("contado", "efectivo");

    /** Builds the typed BDC packet only from customer-confirmed journey facts; never invents fields. */
    public BdcHandoffPacket createPacket(LeadJourneySnapshot journey) {
        if (journey == null) {
            return BdcHandoffPacket.empty();
        }
        var facts = journey.facts();
        var paymentMethod = confirmed(facts, LeadJourneyFactKey.PAYMENT_METHOD);
        var downPayment = paymentMethod.filter(SpecialistHandoffPayloadFactory::isFinancing)
                .flatMap(ignored -> confirmed(facts, LeadJourneyFactKey.DOWN_PAYMENT));
        return new BdcHandoffPacket(
                confirmed(facts, LeadJourneyFactKey.VEHICLE_MODEL),
                paymentMethod,
                downPayment,
                confirmed(facts, LeadJourneyFactKey.PURCHASE_TIMING),
                confirmed(facts, LeadJourneyFactKey.CUSTOMER_NAME));
    }

    private static Optional<String> confirmed(Map<LeadJourneyFactKey, LeadJourneyFact> facts, LeadJourneyFactKey key) {
        var fact = facts == null ? null : facts.get(key);
        if (fact == null || fact.value() == null || fact.value().isBlank()) {
            return Optional.empty();
        }
        var value = fact.value().strip();
        return sensitive(value) ? Optional.empty() : Optional.of(value);
    }

    private static boolean isFinancing(String value) {
        return !NON_FINANCING.contains(normalize(value));
    }

    private static boolean sensitive(String value) {
        return LONG_DIGIT_RUN.matcher(value).find() || EMAIL.matcher(value).find() || PHONE.matcher(value).find();
    }

    public SpecialistHandoffPayload create(String currentUserMessage, ConversationMemoryContext memory,
                                           String assistantInformationAnswer) {
        var customerTurns = customerTurns(currentUserMessage, memory);
        var context = String.join(" ", customerTurns);
        var source = bestReasonSource(customerTurns, currentUserMessage);
        var reasonText = truncate(redact(source), 185);
        var reason = "Solicitud del cliente: «" + reasonText + "»";
        var vehicle = findVehicle(context);
        var needs = findNeed(customerTurns);
        var unresolved = unresolvedQuestion(customerTurns, assistantInformationAnswer);
        return new SpecialistHandoffPayload(reason, vehicle, needs, unresolved);
    }

    private static List<String> customerTurns(String current, ConversationMemoryContext memory) {
        var turns = new ArrayList<String>();
        if (memory != null) {
            memory.recentMessages().stream()
                    .filter(message -> message.direction() == ConversationMessage.Direction.INBOUND)
                    .map(ConversationMessage::content).filter(SpecialistHandoffPayloadFactory::usable)
                    .forEach(turns::add);
        }
        if (usable(current) && (turns.isEmpty() || !turns.get(turns.size() - 1).equals(current))) turns.add(current);
        return List.copyOf(turns);
    }

    private static String bestReasonSource(List<String> turns, String current) {
        if (turns.isEmpty()) return current == null ? "El cliente solicita hablar con un especialista Ford." : current;
        var latest = turns.get(turns.size() - 1);
        var normalized = normalize(latest);
        if (normalized.matches("(si|claro|de acuerdo|acepto|por supuesto|adelante|va|ok|okay)[.! ]*")) {
            for (int i = turns.size() - 2; i >= 0; i--) {
                var candidate = turns.get(i);
                if (!normalize(candidate).matches("(si|claro|de acuerdo|acepto|por supuesto|adelante|va|ok|okay)[.! ]*"))
                    return candidate;
            }
        }
        return latest;
    }

    private static Optional<String> findVehicle(String text) {
        var alternatives = VehicleModelNames.all().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElseThrow();
        var matcher = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(" + alternatives + ")(?![\\p{L}\\p{N}])")
                .matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> findNeed(List<String> turns) {
        for (var turn : turns) {
            var matcher = NEED.matcher(turn);
            while (matcher.find()) {
                var candidate = matcher.group().strip();
                var normalized = normalize(candidate);
                if (normalized.contains("viajar") || normalized.contains("familia") || normalized.contains("espacio")
                        || normalized.contains("ahorro") || normalized.contains("consumo") || normalized.contains("carga")
                        || normalized.contains("prueba de manejo") || normalized.contains("cita")
                        || normalized.contains("trabajo") || normalized.contains("presupuesto")) {
                    var para = normalized.lastIndexOf(" para ");
                    if (para > 0 && !normalized.contains("prueba de manejo")
                            && !(normalized.startsWith("busco espacio") || normalized.startsWith("necesito espacio"))) {
                        candidate = candidate.substring(para + 1);
                    }
                    candidate = candidate.replaceFirst("(?i)\\s+y\\s+(quiero|quisiera|me interesa|necesito|busco|prefiero).*", "")
                            .replaceFirst("[,;].*$", "").strip();
                    return Optional.of(truncate(redact(candidate), 240));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> unresolvedQuestion(List<String> turns, String answer) {
        var normalizedAnswer = normalize(answer);
        var unresolvedEvidence = normalizedAnswer.contains("la vigencia no esta confirmada")
                || normalizedAnswer.contains("plazo registrado") && normalizedAnswer.contains("plazo")
                || normalizedAnswer.contains("los plazos difieren")
                || normalizedAnswer.contains("no coincide") && normalizedAnswer.contains("plazo")
                || normalizedAnswer.contains("difiere") && normalizedAnswer.contains("plazo")
                || normalizedAnswer.contains("discrepancia") && normalizedAnswer.contains("plazo")
                || normalizedAnswer.contains("no se pudo confirmar")
                || normalizedAnswer.contains("falta confirmar");
        if (!unresolvedEvidence) return Optional.empty();
        for (int i = turns.size() - 1; i >= 0; i--) {
            var matcher = QUESTION.matcher(turns.get(i));
            if (matcher.find()) return Optional.of(truncate(redact(matcher.group().strip()), 240));
        }
        for (int i = turns.size() - 1; i >= 0; i--) {
            var normalizedTurn = normalize(turns.get(i));
            if (normalizedTurn.contains("aclarar") || normalizedTurn.contains("confirmar")
                    || normalizedTurn.contains("revisar cual") || normalizedTurn.contains("si el plazo")) {
                return Optional.of(truncate(redact(turns.get(i)), 240));
            }
        }
        return Optional.empty();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean usable(String value) { return value != null && !value.isBlank(); }
    private static String redact(String value) {
        return PHONE.matcher(EMAIL.matcher(value).replaceAll("[dato de contacto omitido]"))
                .replaceAll("[dato de contacto omitido]");
    }
    private static String truncate(String value, int length) {
        var text = value == null ? "" : value.strip();
        return text.length() <= length ? text : text.substring(0, length).strip();
    }
}
