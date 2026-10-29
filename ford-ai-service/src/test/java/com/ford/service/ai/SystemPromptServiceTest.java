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
    void documentsTheRuntimeToolContractAndCsvAlignedConversationRules() throws IOException {
        var resource = new ClassPathResource("prompts/ford-uruapan-system.md");
        var prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        var whatsappFormat = prompt.substring(prompt.indexOf("# FORMATO PARA WHATSAPP"),
                prompt.indexOf("# FORMATO PARA WEB"));

        assertThat(prompt)
                .contains("search_vehicles", "get_vehicle", "compare_vehicles", "get_vehicle_provenance",
                        "get_catalog_status", "list_promotions", "search_offers",
                        "vehicle_snapshot_search", "approved_knowledge_search")
                .contains("se llama MÍA", "trate de usted")
                .contains("Resuelva primero lo que la persona acaba de pedir")
                .contains("## Plantillas de respuesta")
                .contains("`get_vehicle` para un modelo/año exactos")
                .contains("`list_promotions`")
                .contains("El precio publicado no es una cotización final")
                .contains("La vigencia no está confirmada")
                .contains("no calcule ni sugiera que el precio ya incluye el bono")
                 .contains("No agregue otro saludo o presentación")
                 .contains("WhatsApp", "*texto en negritas*", "- elemento", "No use tablas")
                 .contains("# FORMATO PARA WEB", "tarjetas por vehículo", "tabla comparativa",
                         "URLs distintas solo si aportan fuentes diferentes")
                 .contains("amplíe hasta cinco solo si la persona pide más")
                 .as("WhatsApp should allow expanding only after the buyer asks")
                 .satisfies(ignored -> assertThat(whatsappFormat)
                         .contains("tres opciones iniciales", "amplíe hasta cinco solo si la persona pide más"))
                .contains("No mencione herramientas internas, proveedores, JSON")
                .contains("La aplicación hará un reintento silencioso")
                 .contains("no se envía un mensaje de error")
                 .contains("No añada esos detalles a saludos")
                  .contains("una sola pregunta breve", "No repita preferencias ya confirmadas")
                 .contains("No repita una respuesta")
                .contains("No agregue menús de servicios")
                .contains("Horario informado por la agencia: lunes a viernes")
                .contains("sábado de 9:00 a.m. a 2:00 p.m.")
                .contains("siga la autorización que exija la aplicación")
                .contains("Nunca prometa llamada, seguimiento, cita o contacto a primera hora")
                .contains("captura progresiva y opcional")
                .contains("rango de precio")
                .contains("solicitud directa de hablar con un asesor")
                .contains("nunca pida que repita su número de WhatsApp")
                .contains("permiso explícito para compartirlo con Ford en el mismo mensaje")
                .contains("nunca solicite identificación, ingresos, datos bancarios")
                .contains("no significa que un asesor la haya visto, tomado o que ya esté respondiendo")
                .contains("uso principal y una prioridad")
                .contains("Si pide opciones antes")
                .contains("opciones preliminares")
                .contains("no espere una comparación exhaustiva")
                 .contains("opción principal provisional")
                 .contains("hasta dos alternativas")
                 .contains("punto de partida, no como decisión final de compra")
                 .contains("no sustituya con memoria del modelo")
                 .contains("modelos, versiones y precios son datos dinámicos",
                         "Use CAG para recuperar candidatos actuales y su evidencia",
                         "únicamente entre los candidatos devueltos por CAG")
                 .contains("reduzca las opciones de modelos, versiones y trims")
                .contains("promociones después de acotar")
                .contains("Si pregunta por promociones antes")
                .doesNotContain("No pude generar una respuesta", "Aun así, puedo ayudarle con información del catálogo",
                        "Si la fuente no está disponible, dígalo de forma breve");
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
