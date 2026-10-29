package com.ford.service.ai;

import com.ford.service.ai.adapter.out.springai.ObservedToolCallback;
import com.ford.service.ai.application.DecisionTraceCollector;
import com.ford.service.ai.api.AssistantTurnUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.http.HttpHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ObservedToolCallbackTest {

    @Test
    void logsGetVehicleArgumentShapeWithoutRawModelOrTrim(CapturedOutput output) {
        new ObservedToolCallback(evidenceTool("{}"), new SimpleMeterRegistry())
                .call("{\"model\":\"Ranger\",\"year\":2027,\"trim\":\"XLT\"}");

        assertThat(output.getAll()).contains("toolInputShape=modelPresent=true,year=2027,trimPresent=true,trimChars=3")
                .doesNotContain("Ranger", "XLT");
    }

    @Test
    void countsNonEmptySourceBackedObjectAsOneEvidenceItem() {
        var collector = new DecisionTraceCollector();
        var delegate = evidenceTool("{\"model\":\"Ranger\",\"sourceUrl\":\"https://www.forduruapan.mx/RANGER/\"}");
        new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call("{}", traceContext(collector));
        assertThat(collector.snapshot()).filteredOn(event -> "returned".equals(event.outcome()))
                .singleElement().extracting(com.ford.service.ai.application.DecisionTraceEvent::evidenceCount)
                .isEqualTo(1);
    }

    @Test
    void countsEmptyObjectAsNoEvidence() {
        var collector = new DecisionTraceCollector();
        new ObservedToolCallback(evidenceTool("{}"), new SimpleMeterRegistry()).call("{}", traceContext(collector));
        assertThat(collector.snapshot()).filteredOn(event -> "returned".equals(event.outcome()))
                .singleElement().extracting(com.ford.service.ai.application.DecisionTraceEvent::evidenceCount)
                .isEqualTo(0);
    }

    @Test
    void countsArrayEvidenceItems() {
        var collector = new DecisionTraceCollector();
        new ObservedToolCallback(evidenceTool("[{\"id\":1},{\"id\":2},{\"id\":3}]"),
                new SimpleMeterRegistry()).call("{}", traceContext(collector));
        assertThat(collector.snapshot()).filteredOn(event -> "returned".equals(event.outcome()))
                .singleElement().extracting(com.ford.service.ai.application.DecisionTraceEvent::evidenceCount)
                .isEqualTo(3);
    }

    @Test
    void countsEmptyAndNoMatchArraysAsZeroEvidence() {
        for (var result : java.util.List.of("[]", "{\"results\":[]}")) {
            var collector = new DecisionTraceCollector();
            new ObservedToolCallback(evidenceTool(result), new SimpleMeterRegistry())
                    .call("{}", traceContext(collector));
            assertThat(collector.snapshot()).filteredOn(event -> "returned".equals(event.outcome()))
                    .singleElement().extracting(com.ford.service.ai.application.DecisionTraceEvent::evidenceCount)
                    .isEqualTo(0);
        }
    }

    @Test
    void recordsTrimChoicePayloadAsAmbiguousNotAsFactEvidence() {
        var collector = new DecisionTraceCollector();
        var result = "{\"model\":\"RANGER\",\"modelYear\":2027,\"choices\":["
                + "{\"model\":\"RANGER\",\"year\":2027,\"trim\":\"XLT Gasolina 4x2\"},"
                + "{\"model\":\"RANGER\",\"year\":2027,\"trim\":\"XLT Diésel 4x4\"}] }";
        new ObservedToolCallback(evidenceTool(result), new SimpleMeterRegistry()).call("{}", traceContext(collector));

        assertThat(collector.snapshot()).filteredOn(event -> "tool".equals(event.stage()))
                .filteredOn(event -> "ambiguous".equals(event.outcome())).singleElement()
                .satisfies(event -> assertThat(event.evidenceCount()).isZero());
        assertThat(collector.snapshot()).noneMatch(event -> "returned".equals(event.outcome())
                && event.evidenceCount() != null && event.evidenceCount() > 0);
    }

    private static ToolCallback evidenceTool(String result) {
        return new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("get_vehicle").description("vehicle")
                        .inputSchema("{}").build();
            }
            @Override public String call(String input) { return result; }
        };
    }

    private static ToolContext traceContext(DecisionTraceCollector collector) {
        return new ToolContext(Map.of("decisionTraceCollector", collector));
    }

    @Test
    void logsSafeVehicleSearchArgumentShapeOnFailure(CapturedOutput output) {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalArgumentException("catalog search limit must be from 1 to 5");
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, new SimpleMeterRegistry())
                .call("{\"query\":\"private query\",\"model\":\"Ranger\",\"limit\":20}"))
                .isInstanceOf(AssistantTurnUnavailableException.class);

        assertThat(output.getAll())
                .contains("searchInput=queryPresent=true,modelPresent=true,limit=20")
                .doesNotContain("private query");
    }

    @Test
    void logsSafeVehicleSearchFilterShapeOnSuccess(CapturedOutput output) {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "catalog result";
            }
        };

        new ObservedToolCallback(delegate, new SimpleMeterRegistry())
                .call("{\"query\":\"private query\",\"model\":\"Ranger\",\"modelYear\":2026,\"limit\":10}");

        assertThat(output.getAll())
                .contains("toolInputShape=queryPresent=true,modelPresent=true,modelYear=2026,"
                        + "categoryPresent=false,bodyTypePresent=false,fuelTypePresent=false,"
                        + "minPricePresent=false,maxPricePresent=false,limit=10")
                .doesNotContain("private query", "Ranger");
    }

    @Test
    void dropsMalformedYearObjectBeforeDelegating() {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return input;
            }
        };

        var result = new ObservedToolCallback(delegate, new SimpleMeterRegistry())
                .call("{\"query\":\"Ranger\",\"model\":\"Ranger\",\"modelYear\":{\"year\":2027},\"limit\":10}");

        assertThat(result).contains("\"modelYear\":null").doesNotContain("year");
    }

    @Test
    void dropsModelAndYearFiltersNotConfirmedByTheConversationDecision() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input, ToolContext context) {
                return input;
            }

            @Override
            public String call(String input) {
                return input;
            }
        };

        var result = new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call(
                "{\"model\":\"Ranger\",\"modelYear\":2022,\"category\":\"pickup\","
                        + "\"bodyType\":\"crew cab\",\"fuelType\":\"diesel\",\"maxPrice\":900000,\"limit\":5}",
                new ToolContext(Map.of("userMessage", "¿Qué Ford me convendría revisar primero?")));
        var arguments = mapper.readTree(result);

        assertThat(arguments.path("model").isNull()).isTrue();
        assertThat(arguments.path("modelYear").isNull()).isTrue();
        assertThat(arguments.path("category").isNull()).isTrue();
        assertThat(arguments.path("bodyType").isNull()).isTrue();
        assertThat(arguments.path("fuelType").isNull()).isTrue();
        assertThat(arguments.path("maxPrice").isNull()).isTrue();
        assertThat(arguments.path("query").asText()).isEqualTo("¿Qué Ford me convendría revisar primero?");
    }

    @Test
    void logsSafePromotionFilterShapeOnSuccess(CapturedOutput output) {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "promotion result";
            }
        };

        new ObservedToolCallback(delegate, new SimpleMeterRegistry())
                .call("{\"model\":\"Ranger\",\"modelYear\":2026,\"asOf\":\"2026-09-30\",\"activeOnly\":true,\"query\":\"Ranger promotions\"}");

        assertThat(output.getAll())
                .contains("toolInputShape=modelPresent=true,modelYear=2026,asOfPresent=true,asOfMatchesQuery=false,activeOnly=true")
                .doesNotContain("Ranger", "2026-09-30");
    }

    @Test
    void delegatesToolDefinitionMetadataAndContextualCalls() {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String input, ToolContext context) {
                return "promotion-result";
            }

            @Override
            public String call(String input) {
                return "promotion-result";
            }
        };
        var callback = new ObservedToolCallback(delegate, new SimpleMeterRegistry());

        assertThat(callback.getToolDefinition().name()).isEqualTo("list_promotions");
        assertThat(callback.getToolMetadata()).isEqualTo(delegate.getToolMetadata());
        assertThat(callback.call("{}", new ToolContext(Map.of("tenantId", "tenant"))))
                .isEqualTo("promotion-result");
    }

    @Test
    void recordsContentFreeToolLifecycleInTheRequestTrace() {
        var collector = new DecisionTraceCollector();
        var delegate = new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles").description("catalog")
                        .inputSchema("{}").build();
            }
            @Override public String call(String input, ToolContext context) {
                return "{\"results\":[{\"model\":\"Ranger\"}]}";
            }
            @Override public String call(String input) { return call(input, null); }
        };

        new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call(
                "{\"query\":\"secret user query\"}", new ToolContext(Map.of(
                        "tenantId", "tenant", "conversationId", "conversation",
                        "decisionTraceCollector", collector)));

        assertThat(collector.snapshot()).extracting(
                com.ford.service.ai.application.DecisionTraceEvent::outcome).contains("started", "returned");
        assertThat(collector.snapshot().toString()).doesNotContain("secret user query", "Ranger");
    }

    @Test
    void reportsPermanentToolFailureAsReviewWithoutExposingFailureText() {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("source unavailable");
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call("{}"))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
    }

    @Test
    void retriesOneTransientToolFailureSilently() {
        var calls = new AtomicInteger();
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("private source error",
                            new SocketTimeoutException("private source details"));
                }
                return "verified catalog result";
            }
        };

        assertThat(new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call("private input"))
                .isEqualTo("verified catalog result");
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryAuthenticationFailures() {
        var calls = new AtomicInteger();
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                calls.incrementAndGet();
                throw new HttpClientErrorException(HttpStatus.FORBIDDEN);
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call("{}"))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryInvalidRequests() {
        var calls = new AtomicInteger();
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                calls.incrementAndGet();
                throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, new SimpleMeterRegistry()).call("{}"))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.REVIEW));
        assertThat(calls).hasValue(1);
    }

    @Test
    void defersRateLimitWhenRetryAfterExceedsImmediateWaitBudget() {
        var calls = new AtomicInteger();
        var delays = new java.util.ArrayList<Duration>();
        var headers = new HttpHeaders();
        headers.add("Retry-After", "30");
        var failure = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "rate limited", headers, new byte[0], StandardCharsets.UTF_8);
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                calls.incrementAndGet();
                throw failure;
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, new SimpleMeterRegistry(), delays::add)
                .call("{}"))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.RETRY));
        assertThat(calls).hasValue(1);
        assertThat(delays).isEmpty();
    }

    @Test
    void recordsToolOutcomeWithoutLoggingArgumentsOrResults(CapturedOutput output) {
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("list_promotions")
                        .description("offers").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                return "private-promotion-result";
            }
        };

        var registry = new SimpleMeterRegistry();
        var callback = new ObservedToolCallback(delegate, registry);
        assertThat(callback.call("private-tool-arguments"))
                .isEqualTo("private-promotion-result");

        assertThat(output.getOut())
                .contains("catalog tool call started")
                .contains("tool=list_promotions")
                .contains("catalog tool call completed")
                .contains("outcome=success")
                .doesNotContain("private-tool-arguments")
                .doesNotContain("private-promotion-result");
        assertThat(registry.get("ford.ai.tool.attempt")
                .tags("tool", "finance", "outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void classifiesAndCountsToolFailureWithoutLoggingCauseMessage(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        var delegate = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("search_vehicles")
                        .description("catalog").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("outer private value",
                        new SocketTimeoutException("private endpoint and query"));
            }
        };

        assertThatThrownBy(() -> new ObservedToolCallback(delegate, registry).call("private arguments"))
                .isInstanceOf(AssistantTurnUnavailableException.class)
                .satisfies(exception -> assertThat(((AssistantTurnUnavailableException) exception).disposition())
                        .isEqualTo(AssistantTurnUnavailableException.Disposition.RETRY));

        assertThat(output.getOut())
                .contains("failureCategory=timeout")
                .contains("rootCauseType=SocketTimeoutException")
                .doesNotContain("outer private value")
                .doesNotContain("private endpoint and query")
                .doesNotContain("private arguments");
        assertThat(registry.get("ford.ai.tool.attempt")
                .tags("tool", "catalog", "outcome", "failure", "failure_category", "timeout")
                .timer().count()).isEqualTo(1);
    }
}
