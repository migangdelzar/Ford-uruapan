package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalQwenReplayPromptTest {

    @Test
    void addsReplayOnlyConciseSemanticOptionsRulesOnce() {
        var base = "production prompt";

        var extended = LocalQwenReplayPrompt.extend(base);

        assertThat(extended).startsWith(base);
        assertThat(extended).contains("first assistant reply", "search_vehicles", "BGE-M3",
                "OBLIGATORIA DE CATÁLOGO", "hasta tres", "evidencia devuelta", "thanks",
                "one focused question");
        assertThat(LocalQwenReplayPrompt.extend(extended)).isEqualTo(extended);
    }

    @Test
    void statesReplayStyleAndGroundingContractWithoutChangingBasePrompt() {
        var base = "unchanged production system prompt";
        var extended = LocalQwenReplayPrompt.extend(base);

        assertThat(extended).startsWith(base);
        assertThat(extended).contains("Greet only in the first assistant reply",
                "Respeta la memoria confirmada", "versión exacta confirmada",
                "No agregues promociones", "Responde de forma breve y directa",
                "URLs de fuentes confirmadas y pertinentes", "una sola pregunta enfocada");
        assertThat(extended).contains("no pidas una aclaración antes de buscar",
                "qué Ford me convendría revisar primero", "search_vehicles");
        assertThat(base).isEqualTo("unchanged production system prompt");
    }

    @Test
    void setsWarmNaturalMexicanVoiceWithoutRepeatedScriptedClosings() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("español natural de México", "cálido, alegre y optimista",
                "ni uses modismos regionales forzados", "Varía los inicios y el ritmo",
                "No empieces cada respuesta con ‘Entiendo’", "No agregues una invitación genérica a hablar con un asesor",
                "En un agradecimiento sencillo, responde con naturalidad y no hagas otra pregunta");
    }

    @Test
    void appliesExplicitVehicleConstraintsAndExpandsOptionsOnlyWhenBuyerWantsMore() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("filtro obligatorio", "bodyType=pickup",
                "No sustituyas una pickup por una SUV, van o vehículo policial",
                "Empieza con hasta tres opciones", "Amplía hasta cinco solo si el comprador pide más opciones",
                "Si hay menos coincidencias válidas, muestra solo esas");
    }

    @Test
    void standardizesChannelAwareFormattingAndKeepsRecommendationsInsideCagEvidence() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("solo elige y ordena opciones devueltas por CAG",
                "opciones proactivamente cuando el comprador comparte necesidades suficientes",
                "pregunta solo si falta un criterio clave",
                "FORMATO POR CANAL", "WhatsApp", "*negritas*", "_cursivas_",
                "no uses tablas", "Web", "tarjetas", "tabla comparativa",
                "Muestra cada URL una sola vez", "hasta tres opciones",
                "Amplía hasta cinco solo si el comprador pide más opciones");
    }

    @Test
    void keepsChangingCatalogFactsOutOfTheStaticSystemPrompt() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("El system prompt contiene solo reglas estables",
                "no listas completas de modelos, versiones ni precios",
                "Consulta el catálogo vigente mediante CAG", "No inventes ni completes datos faltantes");
    }

    @Test
    void requiresExactAdvisorApprovalForPhysicalInventoryClaims() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("DISPONIBILIDAD FÍSICA",
                "solo comuniques disponibilidad con el texto exacto aprobado por un asesor",
                "No infieras existencias del catálogo, fichas, precio ni promociones");
    }

    @Test
    void resolvesComparisonFollowUpsToTheExactRecentPair() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("COMPARACIÓN Y REFERENCIAS",
                "resuelve “las dos”, “ambas” y “esas versiones” con el par exacto",
                "Llama compare_trims para comparar versiones",
                "para comparar precios de dos versiones, llama compare_trims",
                "Esta regla prevalece sobre la búsqueda general de precios",
                "no uses list_promotions ni get_vehicle individual",
                "No uses search_vehicles como sustituto de esa comparación",
                "Responde solo sobre ese par; no presentes una lista de candidatos",
                "antes de recomendar un modelo o versión como opción para el comprador");
    }

    @Test
    void retrievesTheExactCatalogSourceWhenTheBuyerAsksWhereAFactCameFrom() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("FUENTE DE UN DATO",
                "consulta get_vehicle con el mismo modelo, año y versión",
                "Comparte solo la URL devuelta por la herramienta",
                "no atribuyas una ficha distinta ni inventes el origen");
    }

    @Test
    void retrievesPriceObservationDateFromTheExactPublishedPriceRow() {
        var extended = LocalQwenReplayPrompt.extend("production prompt");

        assertThat(extended).contains("FECHA DE OBSERVACIÓN DEL PRECIO",
                "usa search_vehicles con modelo y año exactos",
                "Responde solo la fecha devuelta para ese precio",
                "si no aparece, di que no está confirmada");
    }
}
