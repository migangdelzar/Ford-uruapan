package com.ford.service.ai.configuration;

import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.application.AiProviderFailureDiagnostics;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.ToolContext;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/** Opt-in, real-provider smoke checks. Inputs and tool results are synthetic. */
class LiveProviderPreflightTest {

    private static final List<String> PROVIDER_IDS = List.of("openai", "cloudflare", "groq", "gemini", "openrouter");

    @TestFactory
    Stream<DynamicTest> enabledProvidersConvertTheAppSchemaAndCallAReadOnlyTool() {
        if (!Boolean.parseBoolean(System.getenv("FORD_AI_LIVE_PROVIDER_TESTS"))) {
            return Stream.of(DynamicTest.dynamicTest("explicit opt-in is required", () ->
                    fail("Set FORD_AI_LIVE_PROVIDER_TESTS=true via mise run ai:provider-preflight")));
        }

        var providerFilter = env("FORD_AI_PREFLIGHT_PROVIDER_IDS");
        var fallbackOrder = System.getenv().getOrDefault("FORD_AI_FALLBACK_ORDER",
                "cloudflare,groq,gemini,openrouter");
        var defaultOrder = (Boolean.parseBoolean(env("FORD_AI_CLOUD_ENABLED")) ? "openai," : "") + fallbackOrder;
        var order = csv(blank(providerFilter) ? defaultOrder : providerFilter);
        var orderedEnabled = new ArrayList<ProviderConfig>();
        for (var id : order) {
            var enabled = id.equals("openai")
                    ? Boolean.parseBoolean(env("FORD_AI_CLOUD_ENABLED"))
                    : Boolean.parseBoolean(env("FORD_AI_" + id.toUpperCase() + "_ENABLED"));
            if (PROVIDER_IDS.contains(id) && enabled) {
                orderedEnabled.add(config(id));
            }
        }
        if (orderedEnabled.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("at least one provider must be enabled", () ->
                    fail("No AI catalog providers are enabled for preflight")));
        }
        return orderedEnabled.stream().map(provider -> DynamicTest.dynamicTest(
                provider.id() + " / " + provider.model(), () -> check(provider)));
    }

    private static void check(ProviderConfig config) {
        if (blank(config.apiKey())) {
            fail("provider=" + config.id() + " missing_api_key");
        }
        var provider = new ChatProviderProperties.Provider();
        provider.setBaseUrl(config.baseUrl());
        provider.setModel(config.model());
        provider.setApiKey(config.apiKey());
        var toolCalls = new AtomicInteger();
        var syntheticTool = new SyntheticLookupTool(toolCalls);
        var callbacks = List.of(ToolCallbacks.from(syntheticTool));
        var client = ChatClient.builder(OpenAiCompatibleChatModelFactory.create(config.id(), provider,
                ObservationRegistry.NOOP)).build();
        var adapter = new SpringAiChatCompletionAdapter(client, config.id(),
                new ToolAuthorizationPolicy(callbacks));
        var tenantId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var request = new AssistantRequest(tenantId, conversationId,
                "Usa lookup_demo exactamente una vez con query DEMO_ITEM. Después responde en español e incluye el resultado DEMO_RESULT_OK.",
                "BOT_ACTIVE", false);
        var started = System.nanoTime();
        try {
            var result = adapter.complete(request, new ToolContext(tenantId, conversationId));
            if (toolCalls.get() != 1 || !"DEMO_ITEM".equals(syntheticTool.query())) {
                fail("provider=" + config.id() + " tool_call_failed calls=" + toolCalls.get());
            }
            if (result.content() == null || result.content().isBlank()) {
                fail("provider=" + config.id() + " structured_response_empty");
            }
            System.out.printf("provider-preflight provider=%s model=%s outcome=pass toolCalls=%d durationMs=%d%n",
                    config.id(), config.model(), toolCalls.get(), elapsedMs(started));
        }
        catch (AssertionError failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            var classified = AiProviderFailureDiagnostics.classify(failure);
            var details = AiProviderFailureDiagnostics.describe(failure, true);
            System.out.printf("provider-preflight provider=%s model=%s outcome=fail category=%s toolCalls=%d diagnostics=%s syntheticProviderError=%s durationMs=%d%n",
                    config.id(), config.model(), classified.category(), toolCalls.get(), details,
                    safeSyntheticError(failure), elapsedMs(started));
            throw new AssertionError("provider=" + config.id() + " category=" + classified.category()
                    + " " + details);
        }
    }

    private static ProviderConfig config(String id) {
        var prefix = "FORD_AI_" + id.toUpperCase();
        var model = id.equals("openai") ? env("FORD_AI_CLOUD_MODEL_VERSION") : env(prefix + "_MODEL");
        var overrideModel = env("FORD_AI_PREFLIGHT_MODEL_" + id.toUpperCase());
        if (!blank(overrideModel)) {
            model = overrideModel;
        }
        if (blank(model)) {
            model = switch (id) {
                case "openai" -> "deepseek-flash";
                case "cloudflare" -> "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
                case "groq" -> "openai/gpt-oss-20b";
                case "gemini" -> "gemini-3.8-flash";
                case "openrouter" -> "openrouter/free";
                default -> throw new IllegalArgumentException("Unknown provider");
            };
        }
        var baseUrl = id.equals("openai") ? env("FORD_AI_CLOUD_BASE_URL") : env(prefix + "_BASE_URL");
        if (blank(baseUrl)) {
            baseUrl = switch (id) {
                case "openai" -> "https://api.deepseek.com";
                case "cloudflare" -> "https://api.cloudflare.com/client/v4/accounts/"
                        + env("CLOUDFLARE_ACCOUNT_ID") + "/ai/v1";
                case "groq" -> "https://api.groq.com/openai/v1";
                case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai/";
                case "openrouter" -> "https://openrouter.ai/api/v1";
                default -> throw new IllegalArgumentException("Unknown provider");
            };
        }
        var apiKey = switch (id) {
            case "openai" -> env("OPENAI_API_KEY");
            case "cloudflare" -> env("CLOUDFLARE_API_TOKEN");
            case "groq" -> env("GROQ_API_KEY");
            case "gemini" -> env("GEMINI_API_KEY");
            case "openrouter" -> env("OPENROUTER_API_KEY");
            default -> "";
        };
        return new ProviderConfig(id, model, baseUrl, apiKey);
    }

    private static String env(String name) {
        return System.getenv().getOrDefault(name, "");
    }

    private static List<String> csv(String value) {
        return Stream.of(value.split(",")).map(String::trim).filter(token -> !token.isBlank()).toList();
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String safeSyntheticError(Throwable failure) {
        var current = failure;
        while (current != null) {
            if (current instanceof com.openai.errors.OpenAIServiceException) {
                var message = current.getMessage();
                if (message == null || message.isBlank()) {
                    return "unavailable";
                }
                var sanitized = message.replaceAll("[\\r\\n\\t]", " ")
                        .replaceAll("(?i)Bearer\\s+[^ ]+", "Bearer [REDACTED]")
                        .strip();
                return sanitized.substring(0, Math.min(sanitized.length(), 240));
            }
            current = current.getCause();
        }
        return "unavailable";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ProviderConfig(String id, String model, String baseUrl, String apiKey) {
    }

    public static final class SyntheticLookupTool {
        private final AtomicInteger calls;
        private volatile String query;

        private SyntheticLookupTool(AtomicInteger calls) {
            this.calls = calls;
        }

        @Tool(name = "lookup_demo", description = "Busca un elemento sintético de prueba. No consulta sistemas reales.")
        public String lookup(@ToolParam(description = "Código sintético a buscar") String query) {
            this.query = query;
            calls.incrementAndGet();
            return "DEMO_RESULT_OK";
        }

        String query() {
            return query;
        }
    }
}
