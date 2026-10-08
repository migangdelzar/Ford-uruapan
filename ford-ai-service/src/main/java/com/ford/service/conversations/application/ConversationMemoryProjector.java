package com.ford.service.conversations.application;

import com.ford.service.conversations.domain.ConversationMemoryContext;
import com.ford.service.conversations.domain.ConversationMessage;
import com.ford.service.conversations.domain.MemoryFact;
import com.ford.service.conversations.domain.MemoryProjectionUpdate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ConversationMemoryProjector {

    private static final List<FactRule> FACT_RULES = List.of(
            new FactRule("name", Pattern.compile(
                    "(?iu)\\b(?:me llamo|mi nombre es|my name is)\\s+(.+?)(?=\\s+(?:y|and)\\s+|[,.!?;]|$)")),
            new FactRule("location", Pattern.compile(
                    "(?iu)\\b(?:vivo en|soy de|estoy en|i live in|i am from)\\s+(.+?)(?=\\s+(?:y|and)\\s+|[,.!?;]|$)")),
            new FactRule("preference", Pattern.compile(
                    "(?iu)\\b(?:prefiero|me gusta|i prefer|i like)\\s+(.+?)(?=\\s+(?:y|and)\\s+|[,.!?;]|$)")),
            new FactRule("vehicle_interest", Pattern.compile(
                    "(?iu)\\b(?:busco|estoy buscando|me interesa|quiero\\s+(?:un|una)|"
                            + "i am looking for|i'm looking for|i want\\s+(?:a|an))\\s+(?:una|un|an|a)?\\s*"
                            + "([^,.!?;]+)")),
            new FactRule("budget", Pattern.compile(
                    "(?iu)\\b(?:mi presupuesto es|presupuesto de|my budget is|up to)\\s+([^,.!?;]+)")));

    private final MemoryProperties properties;
    private final ConversationMemoryCompactionPort compactor;
    private final CustomerHandoffAuthorizationPolicy handoffAuthorization = new CustomerHandoffAuthorizationPolicy();

    public ConversationMemoryProjector(MemoryProperties properties) {
        this(properties, ConversationMemoryCompactionPort.unavailable());
    }

    public ConversationMemoryProjector(MemoryProperties properties, ConversationMemoryCompactionPort compactor) {
        this.properties = Objects.requireNonNull(properties);
        this.compactor = Objects.requireNonNull(compactor);
    }

    public MemoryProjectionUpdate project(ConversationMemoryContext memory,
                                          String latestUserMessage,
                                          UUID sourceMessageId) {
        return project(memory, latestUserMessage, sourceMessageId, null);
    }

    public MemoryProjectionUpdate project(ConversationMemoryContext memory,
                                          String latestUserMessage,
                                          UUID sourceMessageId,
                                          String assistantResponse) {
        var factsByKind = new LinkedHashMap<String, MemoryFact>();
        if (memory != null) {
            memory.durableFacts().forEach(fact -> factsByKind.put(fact.kind(), fact));
        }
        for (var rule : FACT_RULES) {
            var matcher = rule.pattern().matcher(compact(latestUserMessage));
            if (matcher.find()) {
                var content = compact(matcher.group(1));
                if (!content.isBlank()) {
                    factsByKind.put(rule.kind(), new MemoryFact(rule.kind(),
                            truncate(content, properties.factMaxChars()), sourceMessageId));
                }
            }
        }

        if (handoffAuthorization.isDecline(latestUserMessage)) {
            factsByKind.put("specialist_offer_declined", new MemoryFact("specialist_offer_declined",
                    "true", sourceMessageId));
            factsByKind.remove("specialist_offer_offered");
        } else if (handoffAuthorization.isAssistantSpecialistOffer(assistantResponse)
                && !factsByKind.containsKey("specialist_offer_declined")) {
            factsByKind.put("specialist_offer_offered", new MemoryFact("specialist_offer_offered",
                    "true", sourceMessageId));
        }

        var facts = new ArrayList<>(factsByKind.values());
        if (facts.size() > properties.factLimit()) {
            facts = new ArrayList<>(facts.subList(facts.size() - properties.factLimit(), facts.size()));
        }
        var summary = compactOlderWindow(memory);
        return new MemoryProjectionUpdate(summary, facts, sourceMessageId);
    }

    private String compactOlderWindow(ConversationMemoryContext memory) {
        if (memory == null || memory.recentMessages().size() < properties.promptRecentMessageLimit()) {
            return memory == null ? "" : memory.summary();
        }
        var expiredCount = memory.recentMessages().size() - Math.max(0, properties.promptRecentMessageLimit() - 2);
        if (expiredCount < 1) return memory.summary();
        var expired = memory.recentMessages().subList(0, expiredCount);
        try {
            return compactor.compact(memory.key().tenantId(), memory.key().conversationId(),
                    memory.summary(), expired, properties.summaryMaxChars());
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(ConversationMemoryProjector.class).warn(
                    "Conversation memory compaction failed; retaining the last valid summary: conversationId={} exceptionType={}",
                    memory.key().conversationId(), exception.getClass().getSimpleName());
            return memory.summary();
        }
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record FactRule(String kind, Pattern pattern) {
    }
}
