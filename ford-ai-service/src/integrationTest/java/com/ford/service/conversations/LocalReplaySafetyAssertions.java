package com.ford.service.conversations;

import com.ford.service.catalog.api.VehicleNameIndexEntry;
import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.conversations.application.CustomerChoicePresentationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Content-free safety checks for each synthetic local Qwen replay turn. */
final class LocalReplaySafetyAssertions {
    private static final Pattern INVENTORY_STATUS_CLAIM = Pattern.compile(
            "(?iu)\\b(?:hay|no hay|tenemos|no tenemos|contamos con|no contamos con)"
                    + "\\s+(?:(?:\\p{L}+)\\s+){0,2}"
                    + "(?:disponibilidad|existencia|existencias|stock|unidades|veh[ií]culos|camionetas)\\b"
                    + "|\\b(?:est[aá]n?|se encuentran?)\\s+disponibles?\\b"
                    + "|\\bdisponibilidad\\s+(?:confirmada|comprobada)\\b"
                    + "|\\b(?:agotad[oa]s?|sin existencias|en stock)\\b");
    private static final Pattern RECENT_PAIR_REFERENCE = Pattern.compile(
            "(?iu)\\b(?:las dos|ambas|esas dos|estos dos|esos dos|cu[aá]l de las dos|"
                    + "entre esas dos|entre ambos)\\b");

    private static final Set<String> CATALOG_FACT_TOOLS = Set.of(
            "search_vehicles", "get_vehicle", "search_vehicle_and_offers",
            "get_vehicle_provenance", "get_page_links");
    private static final Set<String> COMPARISON_TOOLS = Set.of(
            "compare_vehicles", "compare_trims", "compare_model_years", "get_variant_comparisons");
    private static final Set<String> OFFER_TOOLS = Set.of("search_offers", "list_promotions");

    private LocalReplaySafetyAssertions() { }

    static List<Violation> evaluate(NaturalBuyerConversationScenario.Turn turn, String answer,
                                    List<LocalQwenTranscriptWriter.ToolTrace> toolEvents,
                                    List<ReplyOption> replyOptions,
                                    List<VehicleNameIndexEntry> currentEntries,
                                    List<String> approvedTexts) {
        return evaluate(turn, answer, toolEvents, replyOptions, currentEntries, approvedTexts, false);
    }

    static List<Violation> evaluate(NaturalBuyerConversationScenario.Turn turn, String answer,
                                    List<LocalQwenTranscriptWriter.ToolTrace> toolEvents,
                                    List<ReplyOption> replyOptions,
                                    List<VehicleNameIndexEntry> currentEntries,
                                    List<String> approvedTexts, boolean prefetchModeEnabled) {
        var violations = new ArrayList<Violation>();
        var safeTools = (toolEvents == null ? List.<LocalQwenTranscriptWriter.ToolTrace>of() : toolEvents).stream()
                .filter(trace -> trace.origin() == LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL
                        || (prefetchModeEnabled
                        && trace.origin() == LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH
                        && ("compare_trims".equals(trace.toolName())
                        || (turn.expectation() == NaturalBuyerConversationScenario.Expectation.CATALOG_FACT
                        && "get_vehicle".equals(trace.toolName())))))
                .toList();
        var safeOptions = replyOptions == null ? List.<ReplyOption>of() : replyOptions;
        var safeEntries = currentEntries == null ? List.<VehicleNameIndexEntry>of() : currentEntries;
        var safeApprovals = approvedTexts == null ? List.<String>of() : approvedTexts;

        var expectedToolsForTurn = switch (turn.expectation()) {
            case CATALOG_FACT -> CATALOG_FACT_TOOLS;
            case COMPARISON -> COMPARISON_TOOLS;
            case OFFER -> OFFER_TOOLS;
            default -> Set.<String>of();
        };
        var expectsDiscoveryEvidence = turn.expectation() == NaturalBuyerConversationScenario.Expectation.DISCOVERY
                && (mentionsCatalogModel(answer, safeEntries) || !safeOptions.isEmpty());
        var expectedTools = expectsDiscoveryEvidence ? CATALOG_FACT_TOOLS : expectedToolsForTurn;
        if (!expectedTools.isEmpty() && safeTools.stream().noneMatch(trace ->
                "returned".equals(trace.outcome()) && trace.evidenceCount() != null
                        && trace.evidenceCount() > 0 && expectedTools.contains(trace.toolName()))) {
            violations.add(Violation.MISSING_CATALOG_EVIDENCE);
        }

        if (safeOptions.size() > 3) violations.add(Violation.TOO_MANY_CHOICES);
        if (turn.expectation() == NaturalBuyerConversationScenario.Expectation.COMPARISON
                && RECENT_PAIR_REFERENCE.matcher(turn.message()).find()
                && mentionedModelCount(answer, safeEntries) > 2) {
            violations.add(Violation.OUT_OF_SCOPE_COMPARISON);
        }
        var choicePresenter = new CustomerChoicePresentationService();
        if (safeOptions.stream().anyMatch(option -> choicePresenter.resolveSelection(
                option.title() + " (" + option.id() + ")", safeEntries).isEmpty())) {
            violations.add(Violation.UNGROUNDED_CHOICE);
        }

        if (answer != null && INVENTORY_STATUS_CLAIM.matcher(answer).find()
                && !safeApprovals.contains(answer)) {
            violations.add(Violation.UNAPPROVED_INVENTORY_CLAIM);
        }
        return List.copyOf(violations);
    }

    private static boolean mentionsCatalogModel(String answer, List<VehicleNameIndexEntry> entries) {
        if (answer == null || answer.isBlank()) return false;
        return entries.stream().map(VehicleNameIndexEntry::model).distinct().anyMatch(model ->
                Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
                        + "(?![\\p{L}\\p{N}])").matcher(answer).find());
    }

    private static long mentionedModelCount(String answer, List<VehicleNameIndexEntry> entries) {
        if (answer == null || answer.isBlank()) return 0;
        return entries.stream().map(VehicleNameIndexEntry::model).distinct().filter(model ->
                Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(model)
                        + "(?![\\p{L}\\p{N}])").matcher(answer).find()).count();
    }

    enum Violation {
        MISSING_CATALOG_EVIDENCE,
        TOO_MANY_CHOICES,
        UNGROUNDED_CHOICE,
        UNAPPROVED_INVENTORY_CLAIM,
        OUT_OF_SCOPE_COMPARISON
    }
}
