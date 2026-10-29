package com.ford.service.eval;

import com.ford.service.ai.application.ConversationIntent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IntentEvaluationManifestLoaderTest {

    private final IntentEvaluationManifestLoader loader = new IntentEvaluationManifestLoader();

    @Test
    void rejectsDuplicateCaseIds() {
        var json = manifest("""
                {"caseId":"general-courtesy","generalSubtype":"COURTESY","message":"Gracias.",
                 "expectedIntent":"GENERAL","expectedToolFamily":"NONE","toolMode":"FORBIDDEN",
                 "forbiddenToolFamilies":["VEHICLE_SEARCH"],"expectedBehavior":"ACKNOWLEDGE","notes":"a"},
                {"caseId":"general-courtesy","generalSubtype":"AMBIGUOUS","message":"Hola.",
                 "expectedIntent":"GENERAL","expectedToolFamily":"NONE","toolMode":"FORBIDDEN",
                 "forbiddenToolFamilies":[],"expectedBehavior":"CLARIFY","notes":"b"}
                """);

        assertThatIllegalArgumentException().isThrownBy(() -> load(json))
                .withMessageContaining("duplicate caseId");
    }

    @Test
    void rejectsUnknownIntentAndToolFamily() {
        var unknownIntent = manifest("""
                {"caseId":"unknown-intent","message":"Hola.","expectedIntent":"NOT_REAL",
                 "expectedToolFamily":"NONE","toolMode":"FORBIDDEN","expectedBehavior":"ANSWER","notes":"a"}
                """);
        var unknownTool = manifest("""
                {"caseId":"unknown-tool","message":"Hola.","expectedIntent":"GENERAL",
                 "expectedToolFamily":"NOT_REAL","toolMode":"FORBIDDEN","expectedBehavior":"ANSWER","notes":"a"}
                """);

        assertThatIllegalArgumentException().isThrownBy(() -> load(unknownIntent))
                .withMessageContaining("expectedIntent");
        assertThatIllegalArgumentException().isThrownBy(() -> load(unknownTool))
                .withMessageContaining("expectedToolFamily");
    }

    @Test
    void rejectsMissingGeneralSubtypeAndMismatchedToolMode() {
        var missingSubtype = manifest("""
                {"caseId":"general-no-subtype","message":"Hola.","expectedIntent":"GENERAL",
                 "expectedToolFamily":"NONE","toolMode":"FORBIDDEN","expectedBehavior":"ANSWER","notes":"a"}
                """);
        var forbiddenWithTool = manifest("""
                {"caseId":"general-forbidden-tool","generalSubtype":"NO_TOOL_NEEDED","message":"Hola.",
                 "expectedIntent":"GENERAL","expectedToolFamily":"VEHICLE_SEARCH","toolMode":"FORBIDDEN",
                 "expectedBehavior":"ANSWER","notes":"a"}
                """);

        assertThatIllegalArgumentException().isThrownBy(() -> load(missingSubtype))
                .withMessageContaining("GENERAL requires generalSubtype");
        assertThatIllegalArgumentException().isThrownBy(() -> load(forbiddenWithTool))
                .withMessageContaining("FORBIDDEN requires expectedToolFamily NONE");
    }

    @Test
    void rejectsRequiredModeWithoutToolFamily() {
        var json = manifest("""
                {"caseId":"inventory-required-tool","generalSubtype":null,"message":"Busco una camioneta.",
                 "expectedIntent":"INVENTORY","expectedToolFamily":"NONE","toolMode":"REQUIRED",
                 "expectedBehavior":"ANSWER","notes":"a"}
                """);

        assertThatIllegalArgumentException().isThrownBy(() -> load(json))
                .withMessageContaining("REQUIRED requires a non-NONE expectedToolFamily");
    }

    @Test
    void loadsVersionedFixtureCoveringEveryIntentAndGeneralSubtype() throws Exception {
        var manifest = loader.load(Path.of("src/test/resources/eval/intent-routing-v1.json"));

        assertThat(manifest.suiteId()).isEqualTo("intent-routing");
        assertThat(manifest.version()).isEqualTo("v1");
        assertThat(manifest.cases()).hasSize(22);
        assertThat(manifest.cases()).extracting(IntentEvaluationCase::expectedIntent)
                .contains(ConversationIntent.values());
        assertThat(manifest.cases()).extracting(IntentEvaluationCase::caseId).doesNotHaveDuplicates();
        assertThat(manifest.cases())
                .filteredOn(c -> c.generalSubtype() == IntentEvaluationCase.GeneralSubtype.MIXED_REQUEST)
                .singleElement()
                .satisfies(c -> assertThat(c.expectedIntent())
                        .isNotEqualTo(ConversationIntent.GENERAL));
    }

    private IntentEvaluationManifest load(String json) throws Exception {
        return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String manifest(String cases) {
        return """
                {"suiteId":"loader-test","version":"v1","cases":[%s]}
                """.formatted(cases);
    }
}
