package com.ford.service.leads.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record LeadJourneySnapshot(UUID tenantId,
                                  UUID conversationId,
                                  UUID leadId,
                                  LeadJourneyTopic currentTopic,
                                  Set<LeadJourneyTopic> visitedTopics,
                                  Set<LeadJourneyTopic> pausedTopics,
                                  Map<LeadJourneyFactKey, LeadJourneyFact> facts,
                                  String pendingQuestionKey,
                                  int pendingQuestionAttempts,
                                  String lastProcessedMessageId,
                                  long version,
                                  Instant updatedAt) {
    public LeadJourneySnapshot {
        visitedTopics = visitedTopics == null ? Set.of() : Set.copyOf(visitedTopics);
        pausedTopics = pausedTopics == null ? Set.of() : Set.copyOf(pausedTopics);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
