package com.ford.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.McpFactResponseRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpFactResponseRendererTest {

    private final McpFactResponseRenderer renderer = new McpFactResponseRenderer(
            new DefaultResourceLoader(), new ObjectMapper().findAndRegisterModules());

    @Test
    void rendersConfirmedPriceAndDirectSourceLinksFromMcpEvidence() {
        var output = renderer.render("¿Qué precio tiene Explorer 2027?", Map.of(
                "get_vehicle", """
                        {"model":"Explorer","year":2027,"priceDisplay":"MXN $1,193,600",
                         "modelPageUrl":"https://www.forduruapan.mx/EXPLORER/27/",
                         "priceSourceUrl":"https://www.forduruapan.mx/EXPLORER/27/"}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow())
                .contains("Explorer 2027", "MXN $1,193,600", "Ver precio",
                        "https://www.forduruapan.mx/EXPLORER/27/")
                .contains("*Explorer 2027*", "*Precio publicado:*")
                .doesNotContain("{{", "**", "](");
    }

    @Test
    void rendersConfirmedPromotionDetailsAndValidityFromMcpEvidence() {
        var output = renderer.render("¿Qué promoción tiene Bronco Sport 2026?", Map.of(
                "list_promotions", """
                        {"totalCount":1,"results":[{"model":"Bronco Sport","year":2026,
                         "title":"Bono de temporada","discountAmount":130000,"currency":"MXN",
                         "benefits":["Bono de $130,000 MXN"],"validFrom":"2026-10-01",
                         "validUntil":"2026-11-02","validityStatus":"unknown",
                         "conditions":"Sujeto a aprobación de crédito.",
                         "sourceUrl":"https://www.forduruapan.mx/Promociones/",
                         "modelPageUrl":"https://www.forduruapan.mx/BRONCO-SPORT/26/"}]}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow())
                .contains("Bronco Sport 2026", "Bono de temporada", "Bono de $130,000 MXN",
                        "La vigencia no está confirmada", "2026-10-01", "2026-11-02",
                        "Sujeto a aprobación", "Ver promoción")
                .contains("https://www.forduruapan.mx/Promociones/")
                .contains("🎉 *Promoción para Bronco Sport 2026*", "*Beneficio:*")
                .doesNotContain("{{", "**", "](");
    }

    @Test
    void rendersPriceAndPromotionAsSeparateFactsWithoutSubtractingTheBonus() {
        var output = renderer.render("Precio y promoción de Explorer 2027", Map.of(
                "get_vehicle", """
                        {"model":"Explorer","year":2027,"priceDisplay":"MXN $1,193,600",
                         "modelPageUrl":"https://www.forduruapan.mx/EXPLORER/27/"}
                        """,
                "list_promotions", """
                        {"totalCount":1,"results":[{"model":"Explorer","year":2027,
                         "title":"Bono Explorer","discountAmount":50000,"currency":"MXN",
                         "validityStatus":"active","sourceUrl":"https://www.forduruapan.mx/Promociones/",
                         "modelPageUrl":"https://www.forduruapan.mx/EXPLORER/27/"}]}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("Precio publicado", "Promoción", "MXN $1,193,600",
                "Bono Explorer", "50,000")
                .doesNotContain("1,143,600", "{{");
    }

    @Test
    void doesNotApplyCatalogTemplatesToOtherRequests() {
        assertThat(renderer.render("¿Cuántos asientos tiene Explorer?", Map.of())).isEmpty();
    }

    @Test
    void letsTheModelAnswerComparisonQuestionsEvenWhenPriceWasAlsoMentioned() {
        assertThat(renderer.render("Compara el precio y las versiones de Expedition", Map.of(
                "get_vehicle", "{\"priceDisplay\":\"MXN $1,700,000\"}"))).isEmpty();
    }

    @Test
    void appendsOnlyExactFordMexicoLinksReturnedByComparisonTools() {
        var output = renderer.appendSourceLinks("Estas son las diferencias confirmadas.", Map.of(
                "get_variant_comparisons", """
                        {"modelPageUrl":"https://www.forduruapan.mx/EXPEDITION/27/?utm_source=chat",
                         "technicalSheetUrl":"https://www.forduruapan.mx/EXPEDITION/27/Ficha-Tecnica/",
                         "comparisons":[{"comparisonUrl":"https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/"}]}
                        """,
                "get_vehicle", """
                        {"modelPageUrl":"https://example.com/fake"}
                        """));

        assertThat(output).contains("Estas son las diferencias confirmadas.", "Enlaces útiles",
                "Ver modelo: https://www.forduruapan.mx/EXPEDITION/27/",
                "Ficha técnica: https://www.forduruapan.mx/EXPEDITION/27/Ficha-Tecnica/",
                "Ver comparativo: https://www.forduruapan.mx/EXPEDITION/27/Comparativo/KING-RANCH-MAX/")
                .doesNotContain("example.com", "utm_source", "](");
    }

    @Test
    void doesNotAppendLinksWhenMcpReturnedNoApprovedSourceUrl() {
        assertThat(renderer.appendSourceLinks("No pude confirmarlo.", Map.of(
                "compare_trims", "{\"comparisonUrl\":\"http://forduruapan.mx/compare\"}")))
                .isEqualTo("No pude confirmarlo.");
    }
}
