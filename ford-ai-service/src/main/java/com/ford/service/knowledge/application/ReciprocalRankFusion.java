package com.ford.service.knowledge.application;

import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines independently ranked retrieval lists without comparing their unlike scores. */
public final class ReciprocalRankFusion {

    private static final int RANK_CONSTANT = 60;

    public List<Document> merge(List<Document> vectorResults, List<Document> keywordResults, int limit) {
        Map<String, RankedDocument> ranked = new LinkedHashMap<>();
        addRanks(ranked, vectorResults, true);
        addRanks(ranked, keywordResults, false);
        return ranked.values().stream()
                .sorted((left, right) -> {
                    var scoreOrder = Double.compare(right.score(), left.score());
                    if (scoreOrder != 0) {
                        return scoreOrder;
                    }
                    return Integer.compare(left.keywordRank(), right.keywordRank());
                })
                .limit(limit)
                .map(RankedDocument::document)
                .toList();
    }

    private static void addRanks(Map<String, RankedDocument> ranked, List<Document> documents, boolean vector) {
        for (int index = 0; index < documents.size(); index++) {
            var document = documents.get(index);
            var contribution = 1.0 / (RANK_CONSTANT + index + 1);
            var rank = index + 1;
            ranked.compute(document.getId(), (id, existing) -> {
                if (existing == null) {
                    return new RankedDocument(document, contribution, vector ? Integer.MAX_VALUE : rank);
                }
                return new RankedDocument(existing.document(), existing.score() + contribution,
                        vector ? existing.keywordRank() : Math.min(existing.keywordRank(), rank));
            });
        }
    }

    private record RankedDocument(Document document, double score, int keywordRank) { }
}
