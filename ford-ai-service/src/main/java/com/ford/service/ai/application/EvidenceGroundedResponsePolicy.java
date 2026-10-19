package com.ford.service.ai.application;

import com.ford.service.catalog.api.VehicleModelNames;

import java.util.ArrayList;
import java.util.List;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic hard gate for generated answers: every vehicle model, numeric claim,
 * and URL must appear in this turn's evidence ledger or in the customer's own message,
 * and the answer must not promise delivery, approval, or a response time. It does not judge
 * the truth of tool evidence; it only rejects unverifiable claims and never-promise
 * language before a reply is sent.
 */
public class EvidenceGroundedResponsePolicy {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final List<Pattern> VEHICLE_MODELS = VehicleModelNames.all().stream()
            .sorted(java.util.Comparator.comparingInt(String::length).reversed())
            .map(model -> Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
                    + "(?![\\p{L}\\p{N}])"))
            .toList();
    private static final Pattern URL = Pattern.compile("(?iu)https?://[^\\s)\\]\"']+");
    private static final List<Pattern> NEVER_PROMISE = List.of(
            Pattern.compile("(?iu)\\b(?:le|te|les)\\s+garantiz\\w*"),
            Pattern.compile("(?iu)\\bgarantizad[oa]s?\\b"),
            Pattern.compile("(?iu)\\b(?:su\\s+)?cr[eé]dito\\s+(?:ya\\s+)?(?:est[aá]|fue|queda)\\s+aprob\\w*"),
            Pattern.compile("(?iu)\\b(?:le|se\\s+le|ya)\\s+aprob\\w*"),
            Pattern.compile("(?iu)\\bentrega\\s+(?:inmediata|garantizada|hoy|ma[nñ]ana)\\b"),
            Pattern.compile("(?iu)\\b(?:lo|la|se\\s+lo|se\\s+la)\\s+entregamos\\b"),
            Pattern.compile("(?iu)\\ben\\s+(?:24|48|72)\\s+horas\\b"),
            Pattern.compile("(?iu)\\b(?:el\\s+asesor\\s+)?(?:le|te)\\s+responder[aá]\\s+en\\b"),
            Pattern.compile("(?iu)\\bprecio\\s+final\\b"),
            Pattern.compile("(?iu)\\b100\\s*%\\s+aprobad[oa]\\b"),
            Pattern.compile("(?iu)\\bmensualidad\\s+(?:es|ser[aá])\\s+fij[ao]\\b"),
            Pattern.compile("(?iu)\\b(?:te|le|les)\\s+aseguro\\b"),
            Pattern.compile("(?iu)\\bte\\s+prometo\\b"));

    public String repairInstruction(String reason) {
        return switch (reason == null ? "" : reason) {
            case "unsupported_fact_claim" ->
                    "No incluya cifras, precios, años, porcentajes ni especificaciones que no aparezcan en la información confirmada de esta conversación. Elimine o corrija esos datos.";
            case "unsupported_url" ->
                    "Use únicamente los enlaces confirmados para esta conversación. Elimine cualquier enlace no verificado.";
            case "unsupported_model_name" ->
                    "No menciones ningún modelo Ford salvo que aparezca en el mensaje del cliente o en la evidencia confirmada de esta conversación. Elimina el nombre no confirmado; si el modelo es necesario para responder, pregunta cuál quiso decir. No inventes ni sustituyas otro modelo.";
            case "never_promise" ->
                    "No prometa entregas, aprobaciones de crédito, garantías ni tiempos de respuesta. Reformule la respuesta sin prometer nada.";
            default ->
                    "Responda solo con información confirmada por las fuentes y sin prometer resultados.";
        };
    }

    public Verdict evaluate(String customerMessage, String evidenceText, String answer) {
        if (answer == null || answer.isBlank()) {
            return new Verdict(true, "accepted");
        }
        if (!neverPromiseViolations(answer).isEmpty()) {
            return new Verdict(false, "never_promise");
        }
        var evidence = evidenceText == null ? "" : evidenceText;
        var customer = customerMessage == null ? "" : customerMessage;
        for (var url : matchValues(URL, answer)) {
            if (!evidence.contains(url) && !customer.contains(url)) {
                return new Verdict(false, "unsupported_url");
            }
        }
        var evidenceNumbers = numbers(evidence);
        var customerNumbers = numbers(customer);
        for (var claim : numbers(answer)) {
            if (!supportedBy(evidenceNumbers, claim) && !supportedBy(customerNumbers, claim)) {
                return new Verdict(false, "unsupported_fact_claim");
            }
        }
        for (var model : matchValues(VEHICLE_MODELS, answer)) {
            if (!containsModel(evidence, model) && !containsModel(customer, model)) {
                return new Verdict(false, "unsupported_model_name");
            }
        }
        return new Verdict(true, "accepted");
    }

    public List<String> neverPromiseViolations(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        return NEVER_PROMISE.stream()
                .filter(pattern -> pattern.matcher(answer).find())
                .map(Pattern::pattern)
                .toList();
    }

    private static boolean supportedBy(List<String> known, String claim) {
        for (var candidate : known) {
            if (candidate.equals(claim)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> numbers(String text) {
        var values = new ArrayList<String>();
        var matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            var digits = matcher.group().replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                values.add(digits);
            }
        }
        return values;
    }

    private static List<String> matchValues(Pattern pattern, String text) {
        var values = new ArrayList<String>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private static List<String> matchValues(List<Pattern> patterns, String text) {
        var values = new ArrayList<String>();
        for (var pattern : patterns) values.addAll(matchValues(pattern, text));
        return values;
    }

    private static String normalizeModelText(String text) {
        var normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        return Pattern.compile("[^\\p{L}\\p{N}]+")
                .matcher(normalized).replaceAll(" ").strip();
    }

    private static boolean containsModel(String text, String model) {
        var normalizedText = normalizeModelText(text);
        var normalizedModel = normalizeModelText(model);
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedModel)
                + "(?![\\p{L}\\p{N}])").matcher(normalizedText).find();
    }

    public record Verdict(boolean accepted, String reason) {
    }
}
