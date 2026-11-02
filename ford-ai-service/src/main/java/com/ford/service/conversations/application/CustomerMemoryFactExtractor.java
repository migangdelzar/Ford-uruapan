package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.CustomerShoppingFactState;
import com.ford.service.conversations.domain.CustomerShoppingFactType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CustomerMemoryFactExtractor {

    private static final Pattern MODEL = Pattern.compile("(?iu)\\b(?:ford\\s+)?(bronco(?:\\s+sport)?|escape|explorer|edge|ranger|maverick|mustang|territory|expedition)\\b");
    private static final Pattern MONEY = Pattern.compile("(?iu)(?:presupuesto|budget|hasta|máximo|maximo|tope)[^$\\d]{0,20}(?:\\$\\s*)?([\\d][\\d,.]{2,})\\s*(k|mil|mil(?:es)?(?:\\s+pesos)?|pesos)?");
    private static final Pattern TIMEFRAME = Pattern.compile("(?iu)\\b(?:compro|comprar|quiero comprar|en|dentro de)\\s+(\\d{1,2})\\s*(días|dias|semanas|meses|años|anos)\\b|\\b(?:este mes|próximo mes|proximo mes|este año|proximo año|próximo año)\\b");
    private static final Pattern OFFER = Pattern.compile("(?iu)\\b(?:promoci[oó]n|oferta)\\s*(?:id\\s*)?[:#-]?\\s*([a-z0-9][a-z0-9_-]{2,39})\\b");
    private static final Pattern SENSITIVE = Pattern.compile("(?iu)\\b(?:rfc|curp|n[uú]mero de cuenta|cuenta bancaria|cuenta|score|puntaje|ingreso|gano|sueldo|salario)\\b");
    private static final Pattern ASSISTANT_ATTRIBUTION = Pattern.compile("(?iu)^\\s*(?:el asesor|la asesora|el vendedor|la vendedora|me dijiste|dijiste|el bot|la agencia)\\b");

    public List<CustomerMemoryFactProposal> extract(String customerMessage) {
        if (customerMessage == null || customerMessage.isBlank()
                || customerMessage.length() > 2000
                || SENSITIVE.matcher(customerMessage).find()
                || ASSISTANT_ATTRIBUTION.matcher(customerMessage).find()) {
            return List.of();
        }
        var proposals = new ArrayList<CustomerMemoryFactProposal>();
        var declined = Pattern.compile("(?iu)\\b(?:no me interesa|no quiero|no me gusta|descarto|no considerar[eé])\\b").matcher(customerMessage).find();
        var declineOnly = declined;
        var modelMatcher = MODEL.matcher(customerMessage);
        while (modelMatcher.find()) {
            var model = titleCase(modelMatcher.group(1));
            var evidence = modelMatcher.group();
            var type = model.toLowerCase(Locale.ROOT).contains("bronco sport")
                    ? CustomerShoppingFactType.VEHICLE_TRIM : CustomerShoppingFactType.VEHICLE_MODEL;
            var localModelDecline = Pattern.compile("(?iu)\\b(?:no me interesa|no quiero|no me gusta|descarto|no considerar[eé])\\b[^.!?]{0,35}\\b(?:ford\\s+)?"
                    + Pattern.quote(modelMatcher.group(1)) + "\\b").matcher(customerMessage).find();
            var localModelInterest = Pattern.compile("(?iu)(?<!no )\\b(?:me interesa|me gusta|quiero|busco|ahora s[ií])\\b[^.!?]{0,35}\\b(?:ford\\s+)?"
                    + Pattern.quote(modelMatcher.group(1)) + "\\b").matcher(customerMessage).find();
            if (!localModelDecline && !localModelInterest) continue;
            proposals.add(new CustomerMemoryFactProposal(type, key(model), "Ford " + model, evidence,
                    localModelDecline && !localModelInterest ? CustomerShoppingFactState.DECLINED
                            : CustomerShoppingFactState.ACTIVE));
        }
        var moneyMatcher = MONEY.matcher(customerMessage);
        while (!declined && moneyMatcher.find()) {
            var amount = normalizeAmount(moneyMatcher.group(1), moneyMatcher.group(2));
            proposals.add(new CustomerMemoryFactProposal(CustomerShoppingFactType.SHOPPING_BUDGET,
                    "budget", amount, moneyMatcher.group(), CustomerShoppingFactState.ACTIVE));
        }
        var timeframeMatcher = TIMEFRAME.matcher(customerMessage);
        while (!declined && timeframeMatcher.find()) {
            var value = timeframeMatcher.group().trim().replaceAll("\\s+", " ");
            proposals.add(new CustomerMemoryFactProposal(CustomerShoppingFactType.PURCHASE_TIMEFRAME,
                    "purchase-timeframe", value, timeframeMatcher.group(), CustomerShoppingFactState.ACTIVE));
        }
        var offerMatcher = OFFER.matcher(customerMessage);
        while (offerMatcher.find()) {
            if (declineOnly) {
                var id = offerMatcher.group(1);
                proposals.add(new CustomerMemoryFactProposal(CustomerShoppingFactType.PROMOTION_DECLINE,
                        key(id), id, offerMatcher.group(), CustomerShoppingFactState.DECLINED));
            }
        }
        return List.copyOf(proposals);
    }

    private static String normalizeAmount(String amount, String unit) {
        var normalized = amount.replace(",", "").replace(".", "");
        try {
            long value = Long.parseLong(normalized);
            if (unit != null && unit.toLowerCase(Locale.ROOT).matches("k|mil|miles|mil pesos")) value *= 1000;
            if (value < 1000 || value > 100_000_000) return "";
            return Long.toString(value);
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static String titleCase(String value) {
        var words = value.toLowerCase(Locale.ROOT).split("\\s+");
        var result = new StringBuilder();
        for (var word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    public record CustomerMemoryFactProposal(CustomerShoppingFactType type, String subjectKey,
                                              String value, String exactUserEvidence,
                                              CustomerShoppingFactState state) { }
}
