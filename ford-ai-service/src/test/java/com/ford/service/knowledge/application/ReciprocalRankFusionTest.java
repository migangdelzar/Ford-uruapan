package com.ford.service.knowledge.application;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    void promotesDocumentsFoundByBothKeywordAndVectorSearch() {
        var shared = doc("shared");
        var vectorOnly = doc("vector-only");
        var keywordOnly = doc("keyword-only");

        var result = fusion.merge(List.of(vectorOnly, shared), List.of(keywordOnly, shared), 3);

        assertThat(result).extracting(Document::getId)
                .containsExactly("shared", "keyword-only", "vector-only");
    }

    @Test
    void deduplicatesByDocumentIdAndHonorsResultLimit() {
        var first = doc("first");
        var second = doc("second");

        var result = fusion.merge(List.of(first, second), List.of(first), 1);

        assertThat(result).extracting(Document::getId).containsExactly("first");
    }

    @Test
    void fusesThreeCandidateListsByStableIdWithDeterministicOrdering() {
        var shared = new Candidate("shared", "shared evidence");
        var fullTextOnly = new Candidate("full-text", "full text");
        var trigramOnly = new Candidate("trigram", "trigram");
        var vectorOnly = new Candidate("vector", "vector");
        var lists = List.of(
                List.of(vectorOnly, shared),
                List.of(fullTextOnly, shared),
                List.of(trigramOnly, shared));
        Function<Candidate, String> id = Candidate::stableId;

        var first = fusion.mergeById(lists, id, 4);
        var second = fusion.mergeById(lists, id, 4);

        assertThat(first).extracting(Candidate::stableId)
                .containsExactly("shared", "trigram", "vector", "full-text");
        assertThat(second).extracting(Candidate::stableId).containsExactlyElementsOf(
                first.stream().map(Candidate::stableId).toList());
        assertThat(first).extracting(Candidate::stableId).doesNotHaveDuplicates();
    }

    private record Candidate(String stableId, String evidence) { }

    private static Document doc(String id) {
        return new Document(id, "content", Map.of());
    }
}
