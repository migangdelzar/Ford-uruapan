package com.ford.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.CatalogFactResponseRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;
import java.util.Set;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogFactResponseRendererTest {

    private final CatalogFactResponseRenderer renderer = new CatalogFactResponseRenderer(
            new DefaultResourceLoader(), new ObjectMapper().findAndRegisterModules());

    @Test
    void rendersCurrentOfferBeforeBasePriceForPriceOnlyRequest() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", activeRangerOfferPayload()), Set.of(),
                LocalDate.of(2026, 10, 24));

        assertThat(output).isPresent();
        var text = output.orElseThrow();
        assertThat(text).contains("Bono Ranger", "precio publicado", "MXN $907,000")
                .doesNotContain("887,000", "{{", "No encontré promociones");
        assertThat(text.indexOf("Bono Ranger")).isLessThan(text.indexOf("precio publicado"));
    }

    @Test
    void priceOnlyReplyDoesNotClaimAnOfferWhenLookupIsSuccessfullyEmpty() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", "{\"totalCount\":0,\"results\":[]}"), Set.of(),
                LocalDate.of(2026, 10, 24));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("MXN $907,000", "precio publicado")
                .doesNotContain("No encontré promociones", "no existen promociones");
    }

    @Test
    void explicitPriceAndPromotionRequestReportsNoCurrentOfferWhenNoneIsConfirmed() {
        var output = renderer.renderPriceWithOffers("Precio y promoción Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", "{\"totalCount\":0,\"results\":[]}"), Set.of(),
                LocalDate.of(2026, 10, 24));

        assertThat(output.orElseThrow()).contains("No encontré una oferta vigente confirmada", "MXN $907,000");
    }

    @Test
    void priceOnlyReplyExcludesExpiredAndUnknownOffers() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", """
                        {"totalCount":2,"results":[
                          {"model":"Ranger","year":2027,"title":"Bono vencido",
                           "benefits":["$20,000"],"validityStatus":"expired",
                           "validUntil":"2026-10-01","sourceUrl":"https://www.forduruapan.mx/PROMO/EXPIRED/"},
                          {"model":"Ranger","year":2027,"title":"Bono sin fecha",
                           "benefits":["$30,000"],"validityStatus":"unknown",
                           "sourceUrl":"https://www.forduruapan.mx/PROMO/UNKNOWN/"}
                        ]}
                        """), Set.of(), LocalDate.of(2026, 10, 24));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("MXN $907,000")
                .doesNotContain("Bono vencido", "Bono sin fecha", "PROMO/EXPIRED", "PROMO/UNKNOWN");
    }

    @Test
    void activeStatusCannotOverrideOfferDatesOutsideTheAsOfDate() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", """
                        {"results":[
                          {"model":"Ranger","year":2027,"title":"Venció aunque dice active",
                           "benefits":["Bono de $20,000"],"validityStatus":"active",
                           "validUntil":"2026-10-23","sourceUrl":"https://www.forduruapan.mx/PROMO/PAST/"},
                          {"model":"Ranger","year":2027,"title":"Aún no inicia",
                           "benefits":["Bono de $30,000"],"validityStatus":"active",
                           "validFrom":"2026-10-25","sourceUrl":"https://www.forduruapan.mx/PROMO/FUTURE/"}
                        ]}
                        """), Set.of(), LocalDate.of(2026, 10, 24));

        assertThat(output.orElseThrow()).contains("MXN $907,000")
                .doesNotContain("Venció aunque dice active", "Aún no inicia", "PROMO/PAST", "PROMO/FUTURE");
    }

    @Test
    void priceOnlyReplyExcludesOfferWithTermConflict() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", """
                        {"totalCount":1,"results":[{"model":"Ranger","year":2027,
                         "title":"Bono en conflicto","benefits":["Bono de $20,000"],
                         "validityStatus":"active","termConflict":{"cashPrice":true},
                         "sourceUrl":"https://www.forduruapan.mx/PROMO/CONFLICT/"}]}
                        """), Set.of(), LocalDate.of(2026, 10, 24));

        assertThat(output.orElseThrow()).contains("MXN $907,000")
                .doesNotContain("Bono en conflicto", "PROMO/CONFLICT");
    }

    @Test
    void priceOnlyReplyCaveatsUnknownTrimAndShowsAtMostOneOffer() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", """
                        {"totalCount":2,"results":[
                          {"model":"Ranger","year":2027,"title":"Bono A",
                           "benefits":["Bono de $20,000"],"validityStatus":"active",
                           "sourceUrl":"https://www.forduruapan.mx/PROMO/A/"},
                          {"model":"Ranger","year":2027,"title":"Bono B",
                           "benefits":["Bono de $30,000"],"validityStatus":"active",
                           "sourceUrl":"https://www.forduruapan.mx/PROMO/B/"}
                        ]}
                        """), Set.of(), LocalDate.of(2026, 10, 24));

        var text = output.orElseThrow();
        assertThat(text).contains("Bono A", "no confirma si aplica a la versión XLT")
                .doesNotContain("Bono B", "PROMO/B/");
    }

    @Test
    void priceOnlyReplyCaveatsModelYearOfferWhenNoTrimWasSelected() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", activeRangerOfferPayload()), Set.of(),
                LocalDate.of(2026, 10, 24));

        assertThat(output.orElseThrow()).contains("Bono Ranger", "La fuente no detalla las versiones aplicables.")
                .contains("La elegibilidad depende de las condiciones de la oferta.");
    }

    @Test
    void labelsPromotionalCashPriceSeparatelyFromPublishedBasePrice() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(),
                "list_promotions", """
                        {"results":[{"model":"Ranger","year":2027,"title":"Precio especial",
                         "cashPrice":850000,"currency":"MXN","validityStatus":"active",
                         "sourceUrl":"https://www.forduruapan.mx/PROMO/CASH/"}]}
                        """), Set.of(), LocalDate.of(2026, 10, 24));

        assertThat(output.orElseThrow()).contains("Precio promocional publicado", "$850,000", "$907,000")
                .doesNotContain("Precio especial: La fuente no detalló", "$830,000");
    }

    @Test
    void doesNotTruncateOfferConditionsThatMayContainMaterialTerms() {
        var conditions = "Condición ".repeat(80) + "APROBACIÓN FINAL REQUERIDA";
        var offerPayload = """
                {"results":[{"model":"Ranger","year":2027,"title":"Bono Ranger",
                 "benefits":["Bono de $20,000"],"validityStatus":"active",
                 "conditions":"%s","sourceUrl":"https://www.forduruapan.mx/PROMO/RANGER/"}]}
                """.formatted(conditions);

        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", rangerXltPricePayload(), "list_promotions", offerPayload), Set.of(),
                LocalDate.of(2026, 10, 24));

        assertThat(output.orElseThrow()).contains("APROBACIÓN FINAL REQUERIDA");
    }

    @Test
    void combinedPriceAndOfferReplyDoesNotRepeatTheSameSourceLink() {
        var output = renderer.renderPriceWithOffers("Precio Ranger 2027 XLT", Map.of(
                "get_vehicle", """
                        {"vehicle":{"model":"Ranger","year":2027,"trim":"XLT Gasolina 4x2",
                         "priceDisplay":"MXN $907,000",
                         "priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/"}}
                        """,
                "list_promotions", """
                        {"results":[{"model":"Ranger","year":2027,"title":"Bono Ranger",
                         "benefits":["Bono de $20,000"],"validityStatus":"active",
                         "sourceUrl":"https://www.forduruapan.mx/RANGER/27/"}]}
                        """), Set.of(), LocalDate.of(2026, 10, 24));

        var text = output.orElseThrow();
        assertThat(text.split("https://www\\.forduruapan\\.mx/RANGER/27/", -1)).hasSize(2);
    }

    private static String rangerXltPricePayload() {
        return """
                {"vehicle":{"model":"Ranger","year":2027,"trim":"XLT Gasolina 4x2",
                 "priceDisplay":"MXN $907,000",
                 "priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/XLT/"}}
                """;
    }

    private static String activeRangerOfferPayload() {
        return """
                {"totalCount":1,"results":[{"model":"Ranger","year":2027,
                 "title":"Bono Ranger","discountAmount":20000,"currency":"MXN",
                 "benefits":["Bono de $20,000 MXN"],"validFrom":"2026-10-01",
                 "validUntil":"2026-11-30","validityStatus":"active",
                 "conditions":"Aplican condiciones","sourceUrl":"https://www.forduruapan.mx/Promociones/Ranger/"}]}
                """;
    }

    @Test
    void appendsOnlyApprovedFordKnowledgeSourceLinks() {
        var output = renderer.appendKnowledgeSourceLinks("La garantía cubre el vehículo.", java.util.List.of(
                "https://www.ford.mx/garantia",
                "https://www.forduruapan.mx/garantia",
                "https://forduruapan.mx.example.org/garantia"));

        assertThat(output).contains("La garantía cubre el vehículo.", "https://www.ford.mx/garantia",
                "https://www.forduruapan.mx/garantia")
                .doesNotContain("forduruapan.mx.example.org");
    }

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
                .contains("*Explorer 2027*", "precio publicado de referencia")
                .doesNotContain("{{", "**", "](");
    }

    @Test
    void rendersPriceForTheRequestedTrimInsteadOfTheFirstModelYearResult() {
        var output = renderer.render("¿Qué precio tiene la Ranger 2027 XLT?", Map.of(
                "search_vehicles", """
                        {"results":[
                          {"model":"Ranger","year":2027,"trim":"XL Gasolina 4x2",
                           "priceDisplay":"MXN $775,000",
                           "priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/XL/"},
                          {"model":"Ranger","year":2027,"trim":"XLT Gasolina 4x2",
                           "priceDisplay":"MXN $907,000",
                           "priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/XLT/"}
                        ]}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow())
                .contains("Ranger 2027 XLT Gasolina 4x2", "MXN $907,000")
                .doesNotContain("MXN $775,000");
    }

    @Test
    void appendsCurrencyCodeWhenFormattingStructuredNumericPrice() {
        var output = renderer.render("¿Qué precio tiene la Ranger 2027 XLT Gasolina 4x2?", Map.of(
                "get_vehicle", """
                        {"model":"Ranger","year":2027,"trim":"XLT Gasolina 4x2",
                         "price":907000.00,"currency":"MXN",
                         "modelPageUrl":"https://www.forduruapan.mx/RANGER/27/",
                         "priceSourceUrl":"https://www.forduruapan.mx/RANGER/27/XLT/"}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("$907,000 MXN");
    }

    @Test
    void doesNotMatchAnExtendedTrimWhenTheExactRequestedTrimIsAvailable() {
        var output = renderer.render("¿Qué precio tiene la Ranger 2027 XLT?", Map.of(
                "search_vehicles", """
                        {"results":[
                          {"model":"Ranger","year":2027,"trim":"XLT Sport Gasolina 4x2",
                           "priceDisplay":"MXN $950,000"},
                          {"model":"Ranger","year":2027,"trim":"XLT Gasolina 4x2",
                           "priceDisplay":"MXN $907,000"}
                        ]}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("Ranger 2027 XLT Gasolina 4x2", "MXN $907,000")
                .doesNotContain("XLT Sport", "MXN $950,000");
    }

    @Test
    void doesNotSubstituteAnotherTrimWhenRequestedTrimIsAbsent() {
        var output = renderer.render("¿Qué precio tiene la Ranger 2027 Wildtrak?", Map.of(
                "search_vehicles", rangerPrices()));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("No pude confirmar un precio publicado exacto")
                .doesNotContain("MXN $775,000", "MXN $907,000");
    }

    @Test
    void filtersRequestedModelAndYearBeforeSelectingAPrice() {
        var output = renderer.render("Precio Ranger 2027 XLT", Map.of("search_vehicles", """
                {"results":[
                  {"model":"Explorer","year":2027,"trim":"XLT","priceDisplay":"MXN $1,000,000"},
                  {"model":"Ranger","year":2026,"trim":"XLT","priceDisplay":"MXN $800,000"},
                  {"model":"Ranger","year":2027,"trim":"XLT","priceDisplay":"MXN $907,000"}
                ]}
                """));

        assertThat(output.orElseThrow()).contains("Ranger 2027 XLT", "MXN $907,000")
                .doesNotContain("MXN $1,000,000", "MXN $800,000");
    }

    @Test
    void doesNotSubstituteFuelOrDrivetrainWithinTheRequestedTrim() {
        var output = renderer.render("Precio Ranger 2027 XLT Diesel 4x4", Map.of(
                "search_vehicles", rangerPrices()));

        assertThat(output.orElseThrow()).contains("No pude confirmar un precio publicado exacto")
                .doesNotContain("MXN $775,000", "MXN $907,000");
    }

    @Test
    void matchesXlWithoutConfusingItWithXlt() {
        var output = renderer.render("Precio Ranger 2027 XL", Map.of("search_vehicles", rangerPrices()));

        assertThat(output.orElseThrow()).contains("Ranger 2027 XL Gasolina 4x2", "MXN $775,000")
                .doesNotContain("MXN $907,000");
    }

    @Test
    void rendersEvidenceBackedRangeForAModelYearOnlyRequest() {
        var output = renderer.render("Precio Ranger 2027", Map.of("search_vehicles", rangerPrices()));

        assertThat(output.orElseThrow()).contains("Ranger 2027", "desde MXN $775,000 hasta MXN $907,000");
    }

    private String rangerPrices() {
        return """
                {"results":[
                  {"model":"Ranger","year":2027,"trim":"XL Gasolina 4x2","priceDisplay":"MXN $775,000"},
                  {"model":"Ranger","year":2027,"trim":"XLT Gasolina 4x2","priceDisplay":"MXN $907,000"}
                ]}
                """;
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
                .contains("*Bronco Sport 2026* — Bono de temporada", "*Beneficio:*")
                .doesNotContain("{{", "**", "](");
        assertThat(output.orElseThrow().indexOf("Bono de $130,000 MXN"))
                .isLessThan(output.orElseThrow().indexOf("Bono de temporada"));
    }

    @Test
    void excludesPromotionForDifferentRequestedModelAndYear() {
        var output = renderer.render("¿Qué promoción tiene Ranger 2027?", Map.of("list_promotions", """
                {"results":[{"model":"Bronco Sport","year":2026,"title":"Bono exclusivo",
                 "discountAmount":90000,"sourceUrl":"https://www.forduruapan.mx/BRONCO/"}]}
                """));
        assertThat(output.orElse("")).doesNotContain("Bono exclusivo", "90,000", "BRONCO");
    }

    @Test
    void appendsAtMostTwoDistinctApprovedSourceLinks() {
        var output = renderer.appendSourceLinks("Comparativo confirmado.", Map.of(
                "get_variant_comparisons", """
                {"modelPageUrl":"https://www.forduruapan.mx/RANGER/",
                 "technicalSheetUrl":"https://www.forduruapan.mx/RANGER/Ficha/",
                 "comparisonUrl":"https://www.forduruapan.mx/RANGER/Comparativo/"}
                """,
                "get_vehicle", """
                {"modelPageUrl":"https://www.forduruapan.mx/UNRELATED/",
                 "sourceUrl":"https://evil.example/fabricated"}
                """));
        assertThat(output.split("https://", -1)).hasSize(3);
        assertThat(output).contains("https://www.forduruapan.mx/RANGER/",
                        "https://www.forduruapan.mx/RANGER/Ficha/")
                .doesNotContain("RANGER/Comparativo", "UNRELATED", "evil.example");
    }

    @Test
    void doesNotAppendTheSameSourceWhenOnlyTrailingSlashDiffers() {
        var output = renderer.appendSourceLinks(
                "Ranger: [Ver modelo](https://www.forduruapan.mx/RANGER/27)",
                Map.of("get_vehicle", """
                        {"modelPageUrl":"https://www.forduruapan.mx/RANGER/27/"}
                        """));

        assertThat(output.split("https://www\\.forduruapan\\.mx/RANGER/27/?", -1)).hasSize(2);
        assertThat(output).doesNotContain("Enlaces útiles");
    }

    @Test
    void qualifiesPromotionWhenRequestedTrimIsNotEstablishedByEvidence() {
        var output = renderer.render("¿Qué promoción tiene la Ranger 2027 XLT?", Map.of("list_promotions", """
                {"results":[{"model":"Ranger","year":2027,"title":"Bono Ranger",
                 "validityStatus":"active","sourceUrl":"https://www.forduruapan.mx/PROMO/RANGER/"}]}
                """));
        assertThat(output.orElseThrow()).contains("Bono Ranger", "Ranger 2027")
                .containsIgnoringCase("no confirma si aplica a la versión XLT");
    }

    @Test
    void doesNotClaimRequestedTrimWhenPromotionEvidenceNamesAnotherTrim() {
        var output = renderer.render("¿Qué promoción tiene la Ranger 2027 XLT?", Map.of("list_promotions", """
                {"results":[{"model":"Ranger","year":2027,"trim":"XL","title":"Bono XL",
                 "validityStatus":"active","sourceUrl":"https://www.forduruapan.mx/PROMO/RANGER/"}]}
                """));
        assertThat(output.orElseThrow()).doesNotContain("Bono XL", "aplica a la versión XLT");
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
        assertThat(output.orElseThrow()).contains("precio publicado", "Bono Explorer", "MXN $1,193,600",
                "50,000")
                .doesNotContain("1,143,600", "{{");
        assertThat(output.orElseThrow().indexOf("Bono Explorer"))
                .isLessThan(output.orElseThrow().indexOf("precio publicado"));
    }

    @Test
    void combinedPriceAndPromotionDeduplicateUrlsThatDifferOnlyByTrailingSlash() {
        var output = renderer.render("Precio y promoción de Ranger 2027", Map.of(
                "get_vehicle", """
                        {"model":"Ranger","year":2027,"priceDisplay":"MXN $907,000",
                         "modelPageUrl":"https://www.forduruapan.mx/RANGER/27"}
                        """,
                "list_promotions", """
                        {"results":[{"model":"Ranger","year":2027,"title":"Bono Ranger",
                         "benefits":["Bono publicado"],"sourceUrl":"https://www.forduruapan.mx/RANGER/27/"}]}
                        """));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow().split("https://www\\.forduruapan\\.mx/RANGER/27/?", -1)).hasSize(2);
    }

    @Test
    void rendersVerifiedPromotionAndOmitsPriceWhenOnlyPriceLookupFailed() {
        var output = renderer.render("Precio y promoción de Explorer 2027", Map.of(
                "list_promotions", """
                        {"totalCount":1,"results":[{"model":"Explorer","year":2027,
                         "title":"Bono Explorer","benefits":["Bono de $50,000 MXN"],
                         "validityStatus":"active","validUntil":"2026-11-30",
                         "sourceUrl":"https://www.forduruapan.mx/Promociones/"}]}
                        """), Set.of("get_vehicle"));

        assertThat(output).isPresent();
        assertThat(output.orElseThrow())
                .contains("Bono Explorer", "Bono de $50,000 MXN", "2026-11-30")
                .doesNotContain("Precio publicado", "No pude confirmar", "fuente no está disponible");
    }

    @Test
    void returnsNoTemplateWhenAllRequestedCatalogLookupsFailed() {
        assertThat(renderer.render("Precio y promoción de Explorer 2027", Map.of(),
                Set.of("get_vehicle", "list_promotions"))).isEmpty();
    }

    @Test
    void givesBriefGroundedNoResultWhenPromotionLookupSucceededWithZeroMatches() {
        var output = renderer.render("¿Hay promociones vigentes?", Map.of(
                "list_promotions", """
                        {"totalCount":0,"results":[]}
                        """), Set.of());

        assertThat(output).isPresent();
        assertThat(output.orElseThrow()).contains("No encontré una oferta vigente confirmada")
                .doesNotContain("no existen promociones", "Ventas puede validar");
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
                "Ficha técnica: https://www.forduruapan.mx/EXPEDITION/27/Ficha-Tecnica/")
                .doesNotContain("example.com", "utm_source", "](");
    }

    @Test
    void doesNotAppendLinksWhenMcpReturnedNoApprovedSourceUrl() {
        assertThat(renderer.appendSourceLinks("No pude confirmarlo.", Map.of(
                "compare_trims", "{\"comparisonUrl\":\"http://forduruapan.mx/compare\"}")))
                .isEqualTo("No pude confirmarlo.");
    }
}
