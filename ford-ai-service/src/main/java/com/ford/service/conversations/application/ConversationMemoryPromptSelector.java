package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMemoryRecall;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.CustomerShoppingFact;
import com.ford.service.conversations.domain.MemoryFact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Selects prompt memory without mutating the canonical transcript or the current message. */
public final class ConversationMemoryPromptSelector {

    private final MemoryProperties properties;

    public ConversationMemoryPromptSelector(MemoryProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public Selection select(ConversationMemoryContext context, String currentMessage,
                            List<CustomerShoppingFact> sharedFacts) {
        Objects.requireNonNull(context, "context");
        var selectedFacts = context.durableFacts();
        var selectedSharedFacts = List.copyOf(sharedFacts == null ? List.of() : sharedFacts);
        var current = currentMessage == null ? "" : currentMessage;
        var selectedMessages = new ArrayList<List<ConversationMessage>>();
        var selectedRecalls = new ArrayList<ConversationMemoryRecall>();
        var includedIds = new HashSet<UUID>();
        var omittedRecent = 0;
        var omittedRecall = 0;
        var selectedSummary = "";
        var summaryOmitted = !context.summary().isBlank();

        var estimate = estimate(current, selectedFacts, selectedSharedFacts, selectedSummary,
                selectedMessages, selectedRecalls);
        var overBudget = estimate > properties.promptTokenBudget();

        if (!context.summary().isBlank()) {
            var summaryEstimate = estimate(current, selectedFacts, selectedSharedFacts, context.summary(),
                    selectedMessages, selectedRecalls);
            if (summaryEstimate <= properties.promptTokenBudget()) {
                selectedSummary = context.summary();
                summaryOmitted = false;
                estimate = summaryEstimate;
                overBudget = estimate > properties.promptTokenBudget();
            }
        }

        var turns = completeTurns(context.recentMessages());
        var eligibleTurns = turns.size() <= properties.promptRecentTurnLimit()
                ? turns : turns.subList(turns.size() - properties.promptRecentTurnLimit(), turns.size());
        omittedRecent = context.recentMessages().size() - eligibleTurns.stream().mapToInt(List::size).sum();
        for (var turn : eligibleTurns) {
            var candidate = new ArrayList<>(selectedMessages);
            candidate.add(turn);
            if (estimate(current, selectedFacts, selectedSharedFacts, selectedSummary,
                    candidate, selectedRecalls) <= properties.promptTokenBudget()) {
                selectedMessages.add(turn);
                turn.forEach(message -> includedIds.add(message.id()));
                estimate = estimate(current, selectedFacts, selectedSharedFacts, selectedSummary,
                        selectedMessages, selectedRecalls);
                overBudget = estimate > properties.promptTokenBudget();
            } else {
                omittedRecent += turn.size();
            }
        }

        var recalls = context.recalledTurns().stream()
                .filter(recall -> !includedIds.contains(recall.sourceMessageId()))
                .sorted(Comparator.comparingDouble(ConversationMemoryRecall::similarity).reversed()
                        .thenComparing(ConversationMemoryRecall::occurredAt, Comparator.reverseOrder()))
                .toList();
        omittedRecall = context.recalledTurns().size() - recalls.size();
        for (var recalled : recalls) {
            if (includedIds.contains(recalled.sourceMessageId())) {
                omittedRecall++;
                continue;
            }
            var candidate = new ArrayList<>(selectedRecalls);
            candidate.add(recalled);
            if (estimate(current, selectedFacts, selectedSharedFacts, selectedSummary,
                    selectedMessages, candidate) <= properties.promptTokenBudget()) {
                selectedRecalls.add(recalled);
                includedIds.add(recalled.sourceMessageId());
                estimate = estimate(current, selectedFacts, selectedSharedFacts, selectedSummary,
                        selectedMessages, selectedRecalls);
                overBudget = estimate > properties.promptTokenBudget();
            } else {
                omittedRecall++;
            }
        }

        return new Selection(selectedSummary,
                selectedMessages.stream().flatMap(List::stream).toList(), selectedFacts,
                selectedRecalls, selectedSharedFacts, estimate, omittedRecent, omittedRecall,
                summaryOmitted, overBudget);
    }

    private static List<List<ConversationMessage>> completeTurns(List<ConversationMessage> messages) {
        var result = new ArrayList<List<ConversationMessage>>();
        var current = new ArrayList<ConversationMessage>();
        var hasAssistantReply = false;
        for (var message : messages) {
            if (message.direction() == ConversationMessage.Direction.INBOUND && !current.isEmpty()) {
                if (hasAssistantReply) result.add(List.copyOf(current));
                current.clear();
                hasAssistantReply = false;
            }
            current.add(message);
            if (message.direction() == ConversationMessage.Direction.OUTBOUND) hasAssistantReply = true;
        }
        if (hasAssistantReply) result.add(List.copyOf(current));
        return List.copyOf(result);
    }

    private static int estimate(String current, List<MemoryFact> facts, List<CustomerShoppingFact> sharedFacts,
                                String summary, List<List<ConversationMessage>> messages,
                                List<ConversationMemoryRecall> recalled) {
        var text = new StringBuilder(current).append(' ').append(summary);
        facts.forEach(fact -> text.append(' ').append(fact.kind()).append(' ').append(fact.content()));
        sharedFacts.forEach(fact -> text.append(' ').append(fact.type()).append(' ').append(fact.state())
                .append(' ').append(fact.value()));
        messages.stream().flatMap(List::stream)
                .forEach(message -> text.append(' ').append(message.content()));
        recalled.forEach(turn -> text.append(' ').append(turn.content()));
        var codePoints = text.codePointCount(0, text.length());
        return (codePoints + 1) / 2;
    }

    public record Selection(String summary, List<ConversationMessage> recentMessages,
                            List<MemoryFact> durableFacts, List<ConversationMemoryRecall> recalledTurns,
                            List<CustomerShoppingFact> sharedFacts, int estimatedTokens,
                            int omittedRecentMessages, int omittedRecalledTurns,
                            boolean summaryOmitted, boolean overBudget) {
        public Selection {
            summary = summary == null ? "" : summary;
            recentMessages = List.copyOf(recentMessages == null ? List.of() : recentMessages);
            durableFacts = List.copyOf(durableFacts == null ? List.of() : durableFacts);
            recalledTurns = List.copyOf(recalledTurns == null ? List.of() : recalledTurns);
            sharedFacts = List.copyOf(sharedFacts == null ? List.of() : sharedFacts);
        }
    }
}
