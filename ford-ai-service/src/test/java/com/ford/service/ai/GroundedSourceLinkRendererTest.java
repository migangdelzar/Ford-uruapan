package com.ford.service.ai;

import com.ford.service.ai.application.GroundedSourceLinkRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedSourceLinkRendererTest {

    private final GroundedSourceLinkRenderer renderer = new GroundedSourceLinkRenderer();

    @Test
    void addsOnlyToolReturnedFordLinksAndStripsQueryAndFragment() {
        var answer = "La comparativa ayuda a revisar diferencias. [Ver](https://evil.example/track)";
        var result = renderer.render(answer, List.of(
                "https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/?utm_source=chat#details",
                "https://forduruapan.mx/EXPEDITION/27/"));

        assertThat(result).contains("https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/")
                .contains("https://forduruapan.mx/EXPEDITION/27/")
                .doesNotContain("utm_source", "#details", "evil.example");
    }

    @Test
    void rejectsUntrustedOrMalformedSourceLinks() {
        var result = renderer.render("Respuesta [enlace](https://example.com)", List.of(
                "javascript:alert(1)", "https://forduruapan.mx.evil.example/path"));

        assertThat(result).doesNotContain("https://example.com", "javascript:", "forduruapan.mx.evil.example");
    }
}
