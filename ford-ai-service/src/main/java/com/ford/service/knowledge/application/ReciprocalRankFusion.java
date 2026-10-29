package com.ford.service.knowledge.application;

import org.springframework.ai.document.Document;
import org.springframework.modulith.NamedInterface;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Combines independently ranked retrieval lists without comparing their unlike scores. */
@NamedInterface
public final class ReciprocalRankFusion {

    private static final int RANK_CONSTANT = 60;

    public List<Document> merge(List<Document> vectorResults, List<Document> keywordResults, int limit) {
        return mergeById(List.of(vectorResults, keywordResults), Document::getId, limit);
    }

    /** Combines any number of independently ranked lists by a stable domain identifier. */
    public <T> List<T> mergeById(List<? extends List<T>> candidateLists,
                                 Function<T, String> stableId,
                                 int limit) {
        Objects.requireNonNull(candidateLists, "candidate lists are required");
        Objects.requireNonNull(stableId, "stable id function is required");
        if (limit <= 0 || candidateLists.isEmpty()) return List.of();

        Map<String, Ranked<T>> ranked = new LinkedHashMap<>();
        var firstSeen = 0;
        for (int listIndex = 0; listIndex < candidateLists.size(); listIndex++) {
            var candidates = candidateLists.get(listIndex);
            if (candidates == null || candidates.isEmpty()) continue;
            Set<String> seenInList = new HashSet<>();
            var rank = 0;
            for (var candidate : candidates) {
                if (candidate == null) continue;
                var id = stableId.apply(candidate);
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("stable candidate id is required");
                }
                if (!seenInList.add(id)) continue;
                rank++;
                var contribution = 1.0 / (RANK_CONSTANT + rank);
                var tieRank = listIndex == candidateLists.size() - 1 ? rank : Integer.MAX_VALUE;
                var encounter = firstSeen++;
                ranked.compute(id, (ignored, existing) -> existing == null
                        ? new Ranked<>(candidate, contribution, tieRank, encounter)
                        : new Ranked<>(existing.value(), existing.score() + contribution,
                                tieRank == Integer.MAX_VALUE ? existing.tieRank() : tieRank,
                                existing.firstSeen()));
            }
        }

        return ranked.values().stream()
                .sorted((left, right) -> {
                    var scoreOrder = Double.compare(right.score(), left.score());
                    if (scoreOrder != 0) {
                        return scoreOrder;
                    }
                    var tieOrder = Integer.compare(left.tieRank(), right.tieRank());
                    return tieOrder != 0 ? tieOrder : Integer.compare(left.firstSeen(), right.firstSeen());
                })
                .limit(limit)
                .map(Ranked::value)
                .toList();
    }

    private record Ranked<T>(T value, double score, int tieRank, int firstSeen) { }
}
