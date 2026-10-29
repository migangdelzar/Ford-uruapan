package com.ford.service.conversations.application;

import com.ford.service.conversations.api.BdcHandoffPacket;
import com.ford.service.conversations.api.SpecialistHandoffPayload;
import com.ford.service.conversations.api.InternalHandoffNote;
import com.ford.service.catalog.api.VehicleModelNames;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.DecisionTraceEvent;
import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.leads.domain.LeadJourneyFact;
import com.ford.service.leads.domain.LeadJourneyFactKey;
import com.ford.service.leads.domain.LeadJourneySnapshot;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Builds the handoff payload only from persisted customer turns and the completed information answer. */
public final class SpecialistHandoffPayloadFactory {
    private static final Pattern NEED = Pattern.compile(
            "(?i)\\b(?:busco|buscando|necesito|me interesa|prefiero|para)\\s+[^.!?]{3,100}");
    private static final Pattern QUESTION = Pattern.compile("[^.!?]*\\?+");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\+?\\d[\\d .()-]{8,}\\d(?!\\d)");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{7,}");
    private static final Pattern EXPLICIT_NAME = Pattern.compile(
            "(?iu)\\b(?:me llamo|mi nombre es)\\s+([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*){0,3})");
    private static final Pattern NAME_REPLY = Pattern.compile("(?iu)^[\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*)?[.! ]*$");
    private static final Pattern NAME_CONNECTOR = Pattern.compile("(?iu)\\s+(?:y|e|pero|busco|quiero|necesito|para|con|una|un)$");
    private static final Pattern NAME_SHARING_DECLINE = Pattern.compile(
            "(?iu)\\bno\\s+(?:(?:quiero|quisiera|deseo)\\s+(?:que\\s+)?"
                    + "(?:compartir|comparta|compartas|compartan|usar|use|uses|mencionar|mencione|menciones|decir|diga|digas)"
                    + "|(?:me\\s+)?(?:compartir|comparta|compartas|compartan|use|uses|mencione|menciones|diga|digas))"
                    + "(?:\\s+(?:mi|el|su))?\\s+nombre\\b");

    /** Builds an operator note from confirmed journey facts and bounded tool outcome metadata only. */
    public InternalHandoffNote createInternalNote(LeadJourneySnapshot journey, List<DecisionTraceEvent> trace,
                                                  ConversationDecision decision, String urgency) {
        var facts = new ArrayList<InternalHandoffNote.Fact>();
        if (journey != null) {
            var model = confirmed(journey.facts(), LeadJourneyFactKey.VEHICLE_MODEL)
                    .flatMap(SpecialistHandoffPayloadFactory::canonicalVehicle);
            model.ifPresent(value -> facts.add(new InternalHandoffNote.Fact("vehicle_model", value, "lead_journey")));
        }
        if (decision != null) {
            var entities = decision.vehicleEntities();
            if (entities.modelSource() == com.ford.service.ai.application.VehicleEntityResolution.Source.CURRENT_MESSAGE) {
                entities.model().flatMap(SpecialistHandoffPayloadFactory::canonicalVehicle).ifPresent(value -> {
                    if (facts.stream().noneMatch(fact -> "vehicle_model".equals(fact.field()))) {
                        facts.add(new InternalHandoffNote.Fact("vehicle_model", value, "current_message"));
                    }
                });
            }
            if (entities.modelYearSource() == com.ford.service.ai.application.VehicleEntityResolution.Source.CURRENT_MESSAGE) {
                entities.modelYear().ifPresent(value -> facts.add(new InternalHandoffNote.Fact(
                        "model_year", value.toString(), "current_message")));
            }
            if (entities.trimSource() == com.ford.service.ai.application.VehicleEntityResolution.Source.CURRENT_MESSAGE) {
                entities.trim().ifPresent(value -> facts.add(new InternalHandoffNote.Fact(
                        "vehicle_trim", value, "current_message")));
            }
        }
        var evidence = (trace == null ? List.<DecisionTraceEvent>of() : trace).stream()
                .filter(event -> "tool".equals(event.stage()) && "returned".equals(event.outcome())
                        && event.toolName() != null && !event.toolName().isBlank()
                        && event.evidenceCount() != null && event.evidenceCount() > 0)
                .map(event -> new InternalHandoffNote.Evidence(event.toolName(), event.outcome(),
                        event.evidenceCount(), "not_recorded"))
                .limit(10).toList();
        var unknowns = new ArrayList<String>();
        if (facts.stream().noneMatch(fact -> "vehicle_model".equals(fact.field()))) unknowns.add("vehicle_model");
        if (facts.stream().noneMatch(fact -> "model_year".equals(fact.field()))) unknowns.add("model_year");
        if (facts.stream().noneMatch(fact -> "vehicle_trim".equals(fact.field()))) unknowns.add("trim");
        unknowns.add("evidence_freshness");
        var nextAction = decision == null ? "no_action"
                : decision.nextAction().name().toLowerCase(Locale.ROOT);
        return new InternalHandoffNote("specialist_handoff", urgency, facts, evidence, unknowns, nextAction);
    }

    private static Optional<String> canonicalVehicle(String value) {
        return VehicleModelNames.all().stream().filter(model -> model.equalsIgnoreCase(value)).findFirst();
    }

    /** Builds the typed BDC packet only from customer-confirmed journey facts; never invents fields. */
    public BdcHandoffPacket createPacket(LeadJourneySnapshot journey) {
        return createPacket(journey, null);
    }

    public BdcHandoffPacket createPacket(LeadJourneySnapshot journey, String currentCustomerMessage) {
        return createPacket(journey, currentCustomerMessage, null);
    }

    public BdcHandoffPacket createPacket(LeadJourneySnapshot journey, String currentCustomerMessage,
                                         ConversationMemoryContext memory) {
        var name = explicitCustomerName(currentCustomerMessage, memory);
        var namesForRedaction = customerNamesForRedaction(currentCustomerMessage, memory);
        if (journey == null) {
            return new BdcHandoffPacket(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), name);
        }
        var facts = journey.facts();
        return new BdcHandoffPacket(
                packetFact(facts, LeadJourneyFactKey.VEHICLE_MODEL, namesForRedaction),
                packetFact(facts, LeadJourneyFactKey.VEHICLE_TRIM, namesForRedaction),
                packetFact(facts, LeadJourneyFactKey.VEHICLE_USE, namesForRedaction),
                packetFact(facts, LeadJourneyFactKey.PURCHASE_TIMING, namesForRedaction),
                packetFact(facts, LeadJourneyFactKey.BUDGET_RANGE, namesForRedaction),
                packetFact(facts, LeadJourneyFactKey.TRADE_IN_INTEREST, namesForRedaction),
                packetFact(facts, LeadJourneyFactKey.APPOINTMENT_INTEREST, namesForRedaction),
                name);
    }

    private static Optional<String> packetFact(Map<LeadJourneyFactKey, LeadJourneyFact> facts,
                                               LeadJourneyFactKey key, List<String> namesForRedaction) {
        return confirmed(facts, key).map(value -> stripNames(value, namesForRedaction));
    }

    private static Optional<String> explicitCustomerName(String current, ConversationMemoryContext memory) {
        if (current != null && NAME_SHARING_DECLINE.matcher(normalize(current)).find()) return Optional.empty();
        var direct = explicitName(current);
        if (direct.isPresent()) return direct;
        if (memory == null) return Optional.empty();
        var messages = memory.recentMessages();
        if (!messages.isEmpty()) {
            var previous = messages.get(messages.size() - 1);
            if (previous.direction() == ConversationMessage.Direction.OUTBOUND
                    && askedForName(previous.content())) return nameReply(current);
        }
        var newerInboundSeen = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.direction() == ConversationMessage.Direction.INBOUND) {
                if (NAME_SHARING_DECLINE.matcher(normalize(message.content())).find()) return Optional.empty();
                var historical = explicitName(message.content());
                if (historical.isPresent()) return historical;
                if (!newerInboundSeen && i > 0
                        && messages.get(i - 1).direction() == ConversationMessage.Direction.OUTBOUND
                        && askedForName(messages.get(i - 1).content())) {
                    var prompted = nameReply(message.content());
                    if (prompted.isPresent()) return prompted;
                }
                newerInboundSeen = true;
            }
        }
        return Optional.empty();
    }

    /** Finds customer-stated names only to remove them from free-text fields, including corrected or declined names. */
    private static List<String> customerNamesForRedaction(String current, ConversationMemoryContext memory) {
        var names = new LinkedHashSet<String>();
        explicitName(current).ifPresent(names::add);
        if (memory == null) return List.copyOf(names);
        var messages = memory.recentMessages();
        if (!messages.isEmpty()) {
            var previous = messages.get(messages.size() - 1);
            if (previous.direction() == ConversationMessage.Direction.OUTBOUND && askedForName(previous.content())) {
                nameReply(current).ifPresent(names::add);
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.direction() != ConversationMessage.Direction.INBOUND) continue;
            explicitName(message.content()).ifPresent(names::add);
            if (i > 0
                    && messages.get(i - 1).direction() == ConversationMessage.Direction.OUTBOUND
                    && askedForName(messages.get(i - 1).content())) {
                nameReply(message.content()).ifPresent(names::add);
            }
        }
        return names.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private static Optional<String> explicitName(String text) {
        if (text == null) return Optional.empty();
        var matcher = EXPLICIT_NAME.matcher(text);
        if (!matcher.find()) return Optional.empty();
        var name = matcher.group(1).strip();
        while (NAME_CONNECTOR.matcher(name).find()) name = NAME_CONNECTOR.matcher(name).replaceFirst("").strip();
        return validName(name);
    }

    private static Optional<String> nameReply(String text) {
        if (text == null || !NAME_REPLY.matcher(text.strip()).matches()) return Optional.empty();
        return validName(text.strip().replaceAll("[.! ]+$", ""));
    }

    private static Optional<String> validName(String name) {
        return name.isBlank() || sensitive(name) || name.length() > 60 ? Optional.empty() : Optional.of(name);
    }

    private static boolean askedForName(String assistantText) {
        var normalized = normalize(assistantText);
        return normalized.contains("que nombre") && (normalized.contains("comparta")
                || normalized.contains("compartir") || normalized.contains("compartirle"));
    }

    private static Optional<String> confirmed(Map<LeadJourneyFactKey, LeadJourneyFact> facts, LeadJourneyFactKey key) {
        var fact = facts == null ? null : facts.get(key);
        if (fact == null || fact.value() == null || fact.value().isBlank()) {
            return Optional.empty();
        }
        var value = fact.value().strip();
        return sensitive(value) ? Optional.empty() : Optional.of(value);
    }

    private static boolean sensitive(String value) {
        return LONG_DIGIT_RUN.matcher(value).find() || EMAIL.matcher(value).find() || PHONE.matcher(value).find();
    }

    public SpecialistHandoffPayload create(String currentUserMessage, ConversationMemoryContext memory,
                                           String assistantInformationAnswer) {
        var namesForRedaction = customerNamesForRedaction(currentUserMessage, memory);
        var customerTurns = customerTurns(currentUserMessage, memory).stream()
                .map(turn -> stripNames(turn, namesForRedaction)).toList();
        var context = String.join(" ", customerTurns);
        var source = stripNames(safeReasonSource(bestReasonSource(customerTurns, currentUserMessage),
                currentUserMessage, memory), namesForRedaction);
        var reasonPrefix = "Solicitud del cliente: «";
        // handoff_ticket.reason is varchar(80); reserve space for the prefix and closing quote.
        var reasonText = truncate(redact(source), 80 - reasonPrefix.length() - 1);
        var reason = reasonPrefix + reasonText + "»";
        var vehicle = findVehicle(context);
        var needs = findNeed(customerTurns);
        var unresolved = unresolvedQuestion(customerTurns, assistantInformationAnswer);
        return new SpecialistHandoffPayload(reason, vehicle, needs, unresolved);
    }

    private static String stripNames(String text, List<String> names) {
        if (text == null || names.isEmpty()) return text;
        for (var name : names) {
            var exactName = Pattern.compile("(?iu)(?<![\\p{L}])" + Pattern.quote(name) + "(?![\\p{L}])");
            text = exactName.matcher(text).replaceAll("");
        }
        return text.replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+([,.!?])", "$1").strip();
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

    private static String safeReasonSource(String source, String current, ConversationMemoryContext memory) {
        if (source == null) return "El cliente solicita hablar con un especialista Ford.";
        var explicitName = EXPLICIT_NAME.matcher(source);
        if (explicitName.find()) {
            source = source.substring(0, explicitName.start()) + source.substring(explicitName.end());
        }
        if (isNameReply(current) && immediatelyAfterNamePrompt(memory)) {
            return "El cliente solicita hablar con un especialista Ford.";
        }
        var cleaned = source.strip().replaceAll("^[,;: y]+|[,;: ]+$", "").strip();
        return cleaned.isBlank() ? "El cliente solicita hablar con un especialista Ford." : cleaned;
    }

    private static boolean isNameReply(String text) {
        return text != null && NAME_REPLY.matcher(text.strip()).matches();
    }

    private static boolean immediatelyAfterNamePrompt(ConversationMemoryContext memory) {
        if (memory == null || memory.recentMessages().isEmpty()) return false;
        var previous = memory.recentMessages().get(memory.recentMessages().size() - 1);
        return previous.direction() == ConversationMessage.Direction.OUTBOUND && askedForName(previous.content());
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
