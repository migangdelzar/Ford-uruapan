package com.ford.service.knowledge.application;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

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

    private static Document doc(String id) {
        return new Document(id, "content", Map.of());
    }
}
