package com.ford.service.ai;

import com.ford.service.ai.application.VehicleEntityResolution;
import com.ford.service.ai.application.VehicleEntityResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleEntityResolverTest {
    private final VehicleEntityResolver resolver = new VehicleEntityResolver();

    @Test
    void resolvesCurrentModelYearAndExplicitTrimWithProvenance() {
        var result = resolver.resolve("¿Qué precio tiene la Ranger XLT 2027?", null);

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.RESOLVED);
        assertThat(result.model()).contains("Ranger");
        assertThat(result.modelYear()).contains(2027);
        assertThat(result.trim()).contains("XLT");
        assertThat(result.modelSource()).isEqualTo(VehicleEntityResolution.Source.CURRENT_MESSAGE);
        assertThat(result.modelYearSource()).isEqualTo(VehicleEntityResolution.Source.CURRENT_MESSAGE);
        assertThat(result.trimSource()).isEqualTo(VehicleEntityResolution.Source.CURRENT_MESSAGE);
    }

    @Test
    void resolvesFuelAndDriveQualifiersAsPartOfTheTrim() {
        var result = resolver.resolve(
                "Me interesa la Ranger 2027 XLT Gasolina 4x2. ¿Cuál es su precio publicado?", null);

        assertThat(result.trim()).contains("XLT Gasolina 4x2");
    }

    @Test
    void keepsComparisonYearsExplicitWithoutSelectingOneYear() {
        var result = resolver.resolve("Compara Ranger 2026 vs 2027", null);

        assertThat(result.model()).contains("Ranger");
        assertThat(result.modelYear()).isEmpty();
        assertThat(result.comparisonYears()).containsExactly(2026, 2027);
    }

    @Test
    void resolvesOnlyUniqueMemoryModelAndDoesNotInheritItsYearOrTrim() {
        var result = resolver.resolve("¿Y esa?", "Ford Ranger XLT 2026");

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.RESOLVED);
        assertThat(result.model()).contains("Ranger");
        assertThat(result.modelSource()).isEqualTo(VehicleEntityResolution.Source.MEMORY_REFERENCE);
        assertThat(result.modelYear()).isEmpty();
        assertThat(result.trim()).isEmpty();
    }

    @Test
    void inheritsOnlyUniqueTrimForAnExplicitVersionReference() {
        var result = resolver.resolve("Contéstame sobre esa versión.",
                "Ranger XLT Diésel 4x4 2027 o Ranger XLT Gasolina 4x2 2027");

        assertThat(result.model()).contains("Ranger");
        assertThat(result.modelYear()).contains(2027);
        assertThat(result.trim()).isEmpty();
        assertThat(result.trimSource()).isEqualTo(VehicleEntityResolution.Source.NONE);
    }

    @Test
    void resolvesAnaphoricVersionFromLatestAssistantMessageDespiteOlderVehicleMentions() {
        var memory = "Historial reciente:\n"
                + "- usuario: También revisé Bronco 2026 y Explorer 2025.\n"
                + "- asistente: La Bronco tiene varias opciones.\n"
                + "- usuario: Ahora hablemos de Ranger.\n"
                + "- asistente: La Ranger XLT Gasolina 4x2 2027 tiene precio publicado.";

        var result = resolver.resolve("¿Qué confirma la ficha de esa versión sobre seguridad?", memory);

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.RESOLVED);
        assertThat(result.model()).contains("Ranger");
        assertThat(result.modelYear()).contains(2027);
        assertThat(result.trim()).contains("XLT Gasolina 4x2");
        assertThat(result.modelSource()).isEqualTo(VehicleEntityResolution.Source.MEMORY_REFERENCE);
        assertThat(result.modelYearSource()).isEqualTo(VehicleEntityResolution.Source.MEMORY_REFERENCE);
        assertThat(result.trimSource()).isEqualTo(VehicleEntityResolution.Source.MEMORY_REFERENCE);
    }

    @Test
    void reportsAmbiguousMemoryReferenceInsteadOfGuessing() {
        var result = resolver.resolve("¿Y esa?", "Ford Ranger y Ford Bronco");

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.AMBIGUOUS);
        assertThat(result.model()).isEmpty();
    }

    @Test
    void doesNotConfuseBroncoSportWithTwoDifferentModels() {
        var result = resolver.resolve("¿Qué versiones hay de Bronco Sport?", null);

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.RESOLVED);
        assertThat(result.model()).contains("Bronco Sport");
    }

    @Test
    void reportsMissingWhenNoVehicleEntityExists() {
        var result = resolver.resolve("Hola, tengo una pregunta", null);

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.MISSING);
        assertThat(result.model()).isEmpty();
        assertThat(result.modelYear()).isEmpty();
    }

    @Test
    void reportsConflictingCurrentModelsAsAmbiguous() {
        var result = resolver.resolve("Compara Ranger con Bronco", null);

        assertThat(result.status()).isEqualTo(VehicleEntityResolution.Status.AMBIGUOUS);
        assertThat(result.model()).isEmpty();
    }
}
