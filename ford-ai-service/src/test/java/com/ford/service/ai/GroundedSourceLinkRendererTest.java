package com.ford.service.ai;

import com.ford.service.ai.application.GroundedSourceLinkRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedSourceLinkRendererTest {

    private final GroundedSourceLinkRenderer renderer = new GroundedSourceLinkRenderer();

    @Test
    void appendsOnlyRetrievedFordLinksWithoutQueryOrFragment() {
        var answer = "La Expedition cuesta $1,000,000. https://www.forduruapan.mx/falsa/?x=1";
        var toolResults = List.of("{\"modelPageUrl\":\"https://www.forduruapan.mx/EXPEDITION/27/?utm=x#trim\","
                + "\"comparisonUrl\":\"https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/?color=red\","
                + "\"other\":\"https://attacker.example/fake\"}");

        var rendered = renderer.render(answer, toolResults);

        assertThat(rendered).contains("[Modelo](https://www.forduruapan.mx/EXPEDITION/27/)")
                .contains("[Comparativa](https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/)")
                .doesNotContain("falsa", "utm=x", "color=red", "attacker.example");
    }

    @Test
    void removesUnsupportedFordLinksAndDoesNotInventCitations() {
        assertThat(renderer.render("Información: https://www.forduruapan.mx/guess/27/?x=1", List.of()))
                .isEqualTo("Información:");
    }

    @Test
    void doesNotDuplicateRetrievedLinksAlreadyInAnswer() {
        var url = "https://www.forduruapan.mx/EXPEDITION/27/";

        assertThat(renderer.render("Modelo: " + url, List.of(url)))
                .isEqualTo("Modelo: " + url);
    }
}
