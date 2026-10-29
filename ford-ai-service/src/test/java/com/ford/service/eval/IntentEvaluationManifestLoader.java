package com.ford.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.ToolFamily;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates a synthetic evaluation manifest without exposing message text. */
public final class IntentEvaluationManifestLoader {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,79}");
    private static final Pattern VERSION = Pattern.compile("v[0-9]{1,3}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public IntentEvaluationManifest load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    public IntentEvaluationManifest load(InputStream input) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(input);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("manifest is not valid JSON", malformed);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("manifest must be a JSON object");
        }
        var suiteId = requiredText(root, "suiteId");
        if (!SAFE_ID.matcher(suiteId).matches()) {
            throw invalid("suiteId", "must match " + SAFE_ID);
        }
        var version = requiredText(root, "version");
        if (!VERSION.matcher(version).matches()) {
            throw invalid("version", "must match " + VERSION);
        }
        var casesNode = root.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw invalid("cases", "must be a non-empty array");
        }
        var cases = new ArrayList<IntentEvaluationCase>();
        var seen = new HashSet<String>();
        for (var node : casesNode) {
            var evaluationCase = parseCase(node);
            if (!seen.add(evaluationCase.caseId())) {
                throw invalid(evaluationCase.caseId(), "duplicate caseId");
            }
            cases.add(evaluationCase);
        }
        validateSuite(cases);
        return new IntentEvaluationManifest(suiteId, version, cases);
    }

    private static IntentEvaluationCase parseCase(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("cases", "each case must be an object");
        }
        var caseId = requiredText(node, "caseId");
        if (!SAFE_ID.matcher(caseId).matches()) {
            throw invalid(caseId, "caseId must match " + SAFE_ID);
        }
        var message = requiredText(node, "message");
        if (message.length() > 2000) {
            throw invalid(caseId, "message must be at most 2000 characters");
        }
        var memoryContext = optionalText(node, "memoryContext");
        if (memoryContext.length() > 4000) {
            throw invalid(caseId, "memoryContext must be at most 4000 characters");
        }
        var generalSubtype = optionalEnum(node, "generalSubtype",
                IntentEvaluationCase.GeneralSubtype.class);
        var expectedIntent = requiredEnum(node, "expectedIntent", ConversationIntent.class);
        var expectedToolFamily = requiredEnum(node, "expectedToolFamily", ToolFamily.class);
        var toolMode = requiredEnum(node, "toolMode", IntentEvaluationCase.ToolMode.class);
        var expectedBehavior = requiredEnum(node, "expectedBehavior",
                IntentEvaluationCase.ExpectedBehavior.class);
        var forbidden = new ArrayList<ToolFamily>();
        var forbiddenNode = node.get("forbiddenToolFamilies");
        if (forbiddenNode != null && !forbiddenNode.isNull()) {
            if (!forbiddenNode.isArray()) {
                throw invalid(caseId, "forbiddenToolFamilies must be an array");
            }
            for (var item : forbiddenNode) {
                forbidden.add(parseEnum(item.asText(""), "forbiddenToolFamilies", ToolFamily.class));
            }
        }
        var notes = requiredText(node, "notes");
        if (notes.length() > 500) {
            throw invalid(caseId, "notes must be at most 500 characters");
        }
        return new IntentEvaluationCase(caseId, generalSubtype, message, memoryContext,
                expectedIntent, expectedToolFamily, toolMode, forbidden, expectedBehavior, notes);
    }

    private static void validateSuite(List<IntentEvaluationCase> cases) {
        var intents = EnumSet.noneOf(ConversationIntent.class);
        var generalSubtypes = EnumSet.noneOf(IntentEvaluationCase.GeneralSubtype.class);
        var hasMixedRequest = false;
        for (var evaluationCase : cases) {
            intents.add(evaluationCase.expectedIntent());
            var subtype = evaluationCase.generalSubtype();
            if (evaluationCase.expectedIntent() == ConversationIntent.GENERAL) {
                if (subtype == null) {
                    throw invalid(evaluationCase.caseId(), "GENERAL requires generalSubtype");
                }
                generalSubtypes.add(subtype);
            } else if (subtype == IntentEvaluationCase.GeneralSubtype.MIXED_REQUEST) {
                hasMixedRequest = true;
            } else if (subtype != null) {
                throw invalid(evaluationCase.caseId(),
                        "generalSubtype is allowed only for GENERAL or MIXED_REQUEST cases");
            }
            if (evaluationCase.toolMode() == IntentEvaluationCase.ToolMode.FORBIDDEN
                    && evaluationCase.expectedToolFamily() != ToolFamily.NONE) {
                throw invalid(evaluationCase.caseId(), "FORBIDDEN requires expectedToolFamily NONE");
            }
            if (evaluationCase.toolMode() == IntentEvaluationCase.ToolMode.REQUIRED
                    && evaluationCase.expectedToolFamily() == ToolFamily.NONE) {
                throw invalid(evaluationCase.caseId(),
                        "REQUIRED requires a non-NONE expectedToolFamily");
            }
            if (evaluationCase.forbiddenToolFamilies().contains(evaluationCase.expectedToolFamily())) {
                throw invalid(evaluationCase.caseId(),
                        "forbiddenToolFamilies must not contain expectedToolFamily");
            }
        }
        for (var intent : ConversationIntent.values()) {
            if (!intents.contains(intent)) {
                throw new IllegalArgumentException("manifest is missing intent " + intent.name());
            }
        }
        for (var subtype : List.of(IntentEvaluationCase.GeneralSubtype.COURTESY,
                IntentEvaluationCase.GeneralSubtype.DISCOVERY,
                IntentEvaluationCase.GeneralSubtype.AMBIGUOUS,
                IntentEvaluationCase.GeneralSubtype.NO_TOOL_NEEDED)) {
            if (!generalSubtypes.contains(subtype)) {
                throw new IllegalArgumentException("GENERAL is missing subtype " + subtype.name());
            }
        }
        if (!hasMixedRequest) {
            throw new IllegalArgumentException("manifest is missing a MIXED_REQUEST case");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(field, "must be a non-blank string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw invalid(field, "must be a string");
        return value.asText();
    }

    private static <E extends Enum<E>> E requiredEnum(JsonNode node, String field, Class<E> type) {
        return parseEnum(requiredText(node, field), field, type);
    }

    private static <E extends Enum<E>> E optionalEnum(JsonNode node, String field, Class<E> type) {
        var value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid(field, "must be a string");
        return parseEnum(value.asText(), field, type);
    }

    private static <E extends Enum<E>> E parseEnum(String value, String field, Class<E> type) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            throw invalid(field, "unknown value " + value);
        }
    }

    private static IllegalArgumentException invalid(String field, String message) {
        return new IllegalArgumentException("invalid evaluation manifest field " + field + ": " + message);
    }
}
