package com.ford.service.ai;

import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.SystemPromptSnapshot;
import com.ford.service.ai.application.port.out.SystemPromptRepository;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemPromptServiceTest {

    @Test
    void fallsBackToSeedWhenNoDatabaseOverrideExists() {
        var service = new SystemPromptService(new InMemoryPromptRepository(), "seed prompt", event -> { });

        assertThat(service.currentContent()).isEqualTo("seed prompt");
        assertThat(service.current().version()).isZero();
    }

    @Test
    void replacesSeedWithVersionOne() {
        var repository = new InMemoryPromptRepository();
        var service = new SystemPromptService(repository, "seed prompt", event -> { });

        var updated = service.replace("updated prompt", 0, "operator");

        assertThat(updated.content()).isEqualTo("updated prompt");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(service.currentContent()).isEqualTo("updated prompt");
    }

    @Test
    void rejectsStaleVersionWithoutChangingActivePrompt() {
        var repository = new InMemoryPromptRepository();
        var service = new SystemPromptService(repository, "seed prompt", event -> { });
        service.replace("first prompt", 0, "operator");

        assertThatThrownBy(() -> service.replace("stale prompt", 0, "other-operator"))
                .isInstanceOf(SystemPromptService.VersionConflictException.class);
        assertThat(service.currentContent()).isEqualTo("first prompt");
    }

    @Test
    void documentsTheRuntimeToolContractAndConversationRules() throws IOException {
        var resource = new ClassPathResource("prompts/ford-uruapan-system.md");
        var prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(prompt)
                .contains("search_vehicles", "get_vehicle", "compare_vehicles", "get_vehicle_provenance",
                        "get_catalog_status", "list_promotions", "search_offers",
                        "vehicle_snapshot_search", "approved_knowledge_search")
                .contains("Usted es Valentina")
                .contains("Resuelva primero la pregunta más reciente")
                .contains("No invente precios")
                .contains("No salude de nuevo")
                .contains("No muestre nombres de herramientas, JSON")
                .contains("No repita una respuesta")
                .contains("Valentina Ramírez")
                .contains("¿En qué puedo ayudarle?")
                .contains("aunque no incluya fechas de vigencia")
                .contains("promoción sigue vigente")
                .contains("Atienda únicamente solicitudes Ford Uruapan autorizadas")
                .contains("No responda preguntas generales o ajenas a Ford");
    }

    private static final class InMemoryPromptRepository implements SystemPromptRepository {
        private SystemPromptSnapshot value;

        @Override
        public Optional<SystemPromptSnapshot> find() {
            return Optional.ofNullable(value);
        }

        @Override
        public Optional<SystemPromptSnapshot> replace(String content, long expectedVersion, String updatedBy) {
            var currentVersion = value == null ? 0 : value.version();
            if (currentVersion != expectedVersion) {
                return Optional.empty();
            }
            value = new SystemPromptSnapshot(content, expectedVersion + 1, updatedBy, Instant.now());
            return Optional.of(value);
        }
    }
}
