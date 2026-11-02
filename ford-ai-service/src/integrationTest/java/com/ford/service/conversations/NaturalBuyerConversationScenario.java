package com.ford.service.conversations;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Literal, reviewable synthetic buyer turns for the local 100-turn Qwen replay. */
public final class NaturalBuyerConversationScenario {
    private static final String RESOURCE = "fixtures/meta/natural-buyer-100-turns.json";
    private static final TypeReference<List<Turn>> TURNS_TYPE = new TypeReference<>() { };

    private NaturalBuyerConversationScenario() { }

    public static List<Turn> load(ObjectMapper objectMapper) throws IOException {
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        var turns = objectMapper.readValue(new ClassPathResource(RESOURCE).getInputStream(), TURNS_TYPE);
        if (turns == null || turns.size() != 100) {
            throw new IllegalStateException("natural synthetic buyer scenario must contain exactly 100 turns");
        }
        return List.copyOf(turns);
    }

    public enum Expectation {
        SOCIAL, DISCOVERY, CATALOG_FACT, COMPARISON, FINANCE, OFFER,
        CLARIFICATION, UNAVAILABLE_CAPABILITY, GROUNDED_RECAP, MEMORY_RECALL
    }

    public record Turn(int number, String message, Expectation expectation, List<String> expectedMemoryFacts) {
        public Turn(int number, String message, Expectation expectation) {
            this(number, message, expectation, List.of());
        }

        public Turn {
            if (number < 1 || number > 100 || message == null || message.isBlank()) {
                throw new IllegalArgumentException("scenario turn needs an index from 1 to 100 and a message");
            }
            Objects.requireNonNull(expectation, "scenario expectation is required");
            expectedMemoryFacts = expectedMemoryFacts == null ? List.of() : List.copyOf(expectedMemoryFacts);
        }
    }
}
