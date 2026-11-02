package com.ford.service.conversations;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalBuyerConversationScenarioTest {
    private static final String SCENARIO_RESOURCE = "fixtures/meta/natural-buyer-100-turns.json";
    private static final Set<String> EXPECTATIONS = Set.of(
            "SOCIAL", "DISCOVERY", "CATALOG_FACT", "COMPARISON", "FINANCE",
            "OFFER", "CLARIFICATION", "UNAVAILABLE_CAPABILITY", "GROUNDED_RECAP", "MEMORY_RECALL");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void containsOneHundredDistinctOrderedTurnsAcrossEveryScenarioPhase() throws IOException {
        var turns = objectMapper.readTree(new ClassPathResource(SCENARIO_RESOURCE).getInputStream());

        assertThat(turns.isArray()).isTrue();
        assertThat(turns.size()).isEqualTo(100);
        assertThat(StreamSupport.stream(turns.spliterator(), false)
                .map(turn -> turn.path("number").asInt()).toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 100).boxed().toList());

        var messages = StreamSupport.stream(turns.spliterator(), false)
                .map(turn -> turn.path("message").asText()).toList();
        assertThat(messages).allSatisfy(message -> assertThat(message).isNotBlank());
        assertThat(messages.stream().map(NaturalBuyerConversationScenarioTest::normalize).distinct().count())
                .isEqualTo(100);

        var expectations = StreamSupport.stream(turns.spliterator(), false)
                .map(turn -> turn.path("expectation").asText()).collect(java.util.stream.Collectors.toSet());
        assertThat(expectations).containsExactlyInAnyOrderElementsOf(EXPECTATIONS);
        assertThat(turns.get(0).path("expectation").asText()).isEqualTo("SOCIAL");
        assertThat(turns.get(99).path("expectation").asText()).isEqualTo("GROUNDED_RECAP");
        assertThat(NaturalBuyerConversationScenario.load(objectMapper)).hasSize(100);
    }

    @Test
    void usesSourceConfirmedCatalogFactsAndKeepsFinanceExplicitlySynthetic() throws IOException {
        var turns = NaturalBuyerConversationScenario.load(objectMapper);
        var messages = turns.stream().map(NaturalBuyerConversationScenario.Turn::message)
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(normalize(messages)).doesNotContain("maverick 2027", "catalogo sintetico",
                "promocion sintetica", "899000");
        assertThat(messages).contains("Ranger 2027 XLT Gasolina 4x2");
        assertThat(normalize(messages)).contains("financiamiento sintetico");
    }

    @Test
    void replacesOnlyInventoryPromptsWithExpectedBuyerMemoryProbes() throws IOException {
        var turns = NaturalBuyerConversationScenario.load(objectMapper);

        assertThat(turns).hasSize(100);
        assertThat(turns.get(66).message())
                .isEqualTo("¿Qué uso te comenté para la camioneta entre semana y los fines de semana?");
        assertThat(turns.get(66).expectation()).isEqualTo(NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL);
        assertThat(turns.get(66).expectedMemoryFacts()).containsExactly("weekday_work", "weekend_family");
        assertThat(turns.get(68).message()).isEqualTo("¿Qué cosas dije que quería llevar en la caja?");
        assertThat(turns.get(68).expectation()).isEqualTo(NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL);
        assertThat(turns.get(68).expectedMemoryFacts()).containsExactly("bicycles", "camping_gear");
        assertThat(turns.get(69).message())
                .isEqualTo("¿Qué versión exacta de Ranger revisamos cuando pregunté su precio?");
        assertThat(turns.get(69).expectation()).isEqualTo(NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL);
        assertThat(turns.get(69).expectedMemoryFacts()).containsExactly("ranger_2027_xlt_gasolina_4x2");
        assertThat(turns.stream().filter(turn -> turn.expectation()
                == NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL)).hasSize(3);
    }

    private static String normalize(String message) {
        return Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
