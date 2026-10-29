package com.ford.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.client.OpenAIClientImpl;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapter;
import com.ford.service.ai.application.AiProviderFailureDiagnostics;
import com.ford.service.ai.application.ModelProviderPolicy;
import com.ford.service.ai.application.ProviderCapabilities;
import com.ford.service.ai.application.ProviderDescriptor;
import com.ford.service.ai.application.ToolContext;
import com.ford.service.ai.application.port.out.ProviderChatCompletionPort;
import com.ford.service.ai.application.port.out.ProviderRoutingChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HarnessExecutionSeamTest {
    private static final String TOOL_CALL = """
            {"id":"chatcmpl-synthetic-tool","object":"chat.completion","created":1,"model":"synthetic-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-synthetic","type":"function","function":{"name":"synthetic_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;
    private static final String FINAL = """
            {"id":"chatcmpl-synthetic-final","object":"chat.completion","created":2,"model":"synthetic-model","choices":[{"index":0,"message":{"role":"assistant","content":"done","tool_calls":[]},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
            """;

    @Test
    void springAiStreamDisposalCharacterizesCancellationPropagationToSdkTransportFuture() throws Exception {
        CompletableFuture<HttpResponse> transportFuture = new CompletableFuture<>();
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        HttpClient transport = new HttpClient() {
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                throw new UnsupportedOperationException("sync path not used");
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                dispatchStarted.countDown();
                return transportFuture;
            }
            @Override public void close() { }
        };
        OpenAiChatModel model = openAiModel(openAiClient(transport, 0));
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        var subscription = model.stream(new Prompt("synthetic request"))
                .subscribe(ignored -> { }, streamFailure::set);

        try {
            assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS), "async SDK dispatch did not start");
            subscription.dispose();
            assertFalse(transportFuture.isCancelled(), "stream disposal did not cancel SDK transport future");
        } finally {
            subscription.dispose();
            if (!transportFuture.isDone()) {
                transportFuture.completeExceptionally(new CancellationException("synthetic test cleanup"));
            }
        }
    }

    @Test
    void asyncSdkChargesBeforeForwardingAndCharacterizesCancellationPropagation() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<HttpResponse> transportFuture = new CompletableFuture<>();
        HttpClient chargedTransport = new HttpClient() {
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                throw new UnsupportedOperationException("sync path not used");
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                events.add("forward-async");
                return transportFuture;
            }
            @Override public void close() { }
        };
        HttpClient chargingClient = new HttpClient() {
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                throw new UnsupportedOperationException("sync path not used");
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                events.add("charge-async");
                return chargedTransport.executeAsync(request, options);
            }
            @Override public void close() { }
        };
        var client = openAiClient(chargingClient, 0);
        var params = com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                .model("synthetic-model").addUserMessage("synthetic request").build();

        var sdkFuture = client.async().chat().completions().create(params);

        assertEquals(List.of("charge-async", "forward-async"), events);
        assertFalse(sdkFuture.isDone());
        assertTrue(sdkFuture.cancel(true), "SDK future should accept cancellation");
        assertTrue(sdkFuture.isCancelled(), "SDK future should report cancelled state");
        // Cancellation is characterized from the SDK to the actual transport future.
        assertFalse(transportFuture.isCancelled(), "SDK cancellation did not reach the HTTP transport future");
    }

    @Test
    void actualSpringModelRetriesThenInvokesNativeToolAndSendsContinuation() {
        List<String> events = new ArrayList<>();
        AtomicInteger attempt = new AtomicInteger();
        HttpClient transport = scriptedTransport(events, attempt, 503, TOOL_CALL, FINAL);
        HttpClient chargedTransport = new HttpClient() {
            private int charges;
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                events.add("charge-" + ++charges);
                return transport.execute(request, options);
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                events.add("charge-async");
                return transport.executeAsync(request, options);
            }
            @Override public void close() { }
        };
        var client = new OpenAIClientImpl(com.openai.core.ClientOptions.builder()
                .apiKey("synthetic-test-key")
                .baseUrl("https://synthetic.invalid/v1")
                .httpClient(chargedTransport)
                .maxRetries(1)
                .build());
        var callbackDelegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("synthetic_tool").description("synthetic fixture")
                        .inputSchema("{}") .build();
            }
            @Override public String call(String input) {
                events.add("delegate");
                return "synthetic-result";
            }
        };
        var callback = chargedCallback(callbackDelegate, events, false);
        var model = OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model("synthetic-model").build())
                .build();

        var response = ChatClient.builder(model).build().prompt()
                .user("synthetic request").tools(callback).call().content();

        assertEquals(List.of("charge-1", "send-1", "charge-2", "send-2", "tool-charge", "delegate",
                "charge-3", "send-3"), events);
        assertEquals("done", response);
    }

    @Test
    void deniedToolChargePreventsDelegateAndRecordsSpringContinuationBehavior() {
        List<String> events = new ArrayList<>();
        AtomicInteger sends = new AtomicInteger();
        var client = openAiClient(scriptedTransport(events, sends, TOOL_CALL, FINAL), 1);
        var model = openAiModel(client);
        var delegateCalled = new AtomicInteger();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("synthetic_tool").description("synthetic fixture")
                        .inputSchema("{}").build();
            }
            @Override public String call(String input) {
                delegateCalled.incrementAndGet();
                events.add("delegate");
                return "synthetic-result";
            }
        };

        var denial = new TestBudgetDenial(BudgetReason.TOOL_CALL_EXHAUSTED);
        var deniedCallback = chargedCallback(delegate, events, denial);
        var failure = assertThrows(TestBudgetDenial.class, () -> ChatClient.builder(model).build().prompt()
                .user("synthetic request").tools(deniedCallback).call().content());

        assertEquals(0, delegateCalled.get());
        assertTrue(events.contains("tool-charge-denied"));
        assertTrue(causeChainContainsIdentity(failure, denial));
        assertEquals(BudgetReason.TOOL_CALL_EXHAUSTED, failure.reason());
        // Actual Spring behavior is fail-fast: callback denial propagates; no model continuation request.
        assertEquals(1, sends.get());
        assertEquals(List.of("send-1", "tool-charge-denied"), events);
    }

    @Test
    void typedProviderDenialSurvivesAdapterAndRouterWithoutFallbackSend() {
        List<String> events = new ArrayList<>();
        AtomicInteger sends = new AtomicInteger();
        var denial = new TestBudgetDenial(BudgetReason.PROVIDER_ATTEMPT_EXHAUSTED);
        HttpClient deniedTransport = new HttpClient() {
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                events.add("provider-denied");
                throw denial;
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                return CompletableFuture.failedFuture(denial);
            }
            @Override public void close() { }
        };
        var model = openAiModel(openAiClient(deniedTransport, 1));
        var adapter = new SpringAiChatCompletionAdapter(ChatClient.builder(model).build(), "openai");
        var fallbackSends = new AtomicInteger();
        ProviderChatCompletionPort fallback = new ProviderChatCompletionPort() {
            @Override public String providerId() { return "groq"; }
            @Override public AssistantResponse complete(AssistantRequest request, ToolContext context) {
                fallbackSends.incrementAndGet();
                return new AssistantResponse("fallback", false, "groq", List.of());
            }
        };
        var descriptors = List.of(descriptor("openai"), descriptor("groq"));
        ModelProviderPolicy policy = new com.ford.service.ai.application.DefaultModelProviderPolicy(descriptors);
        var router = new ProviderRoutingChatCompletionPort(List.of(adapter, fallback), policy,
                2, java.time.Duration.ZERO, ignored -> { });
        var failure = assertThrows(RuntimeException.class, () -> router.complete(
                new AssistantRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                        "synthetic request", "BOT_ACTIVE", false),
                new ToolContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())));

        assertTrue(causeChainContainsIdentity(failure, denial),
                "typed denial instance must remain in the thrown cause chain; got " + failure.getClass());
        assertEquals(BudgetReason.PROVIDER_ATTEMPT_EXHAUSTED, denial.reason());
        assertEquals(0, fallbackSends.get());
        assertEquals(List.of("provider-denied"), events);
        assertEquals(false, AiProviderFailureDiagnostics.classify(failure).retryable());
    }

    private static ToolCallback chargedCallback(ToolCallback delegate, List<String> events, boolean deny) {
        return chargedCallback(delegate, events,
                deny ? new TestBudgetDenial(BudgetReason.TOOL_CALL_EXHAUSTED) : null);
    }

    private static ToolCallback chargedCallback(ToolCallback delegate, List<String> events, TestBudgetDenial denial) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public String call(String input) {
                events.add(denial == null ? "tool-charge" : "tool-charge-denied");
                if (denial != null) throw denial;
                return delegate.call(input);
            }
            @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                events.add(denial == null ? "tool-charge" : "tool-charge-denied");
                if (denial != null) throw denial;
                return delegate.call(input, context);
            }
        };
    }

    private static OpenAIClientImpl openAiClient(HttpClient httpClient, int retries) {
        return new OpenAIClientImpl(com.openai.core.ClientOptions.builder().apiKey("synthetic-test-key")
                .baseUrl("https://synthetic.invalid/v1").httpClient(httpClient).maxRetries(retries).build());
    }

    private static OpenAiChatModel openAiModel(OpenAIClientImpl client) {
        return OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model("synthetic-model").build()).build();
    }

    private static ProviderDescriptor descriptor(String id) {
        return new ProviderDescriptor(id, new ProviderCapabilities(true, true, true), true, true, id + "-test");
    }

    private static boolean causeChainContainsIdentity(Throwable failure, Throwable expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current == expected) return true;
        }
        return false;
    }

    private enum BudgetReason { PROVIDER_ATTEMPT_EXHAUSTED, TOOL_CALL_EXHAUSTED }

    private static final class TestBudgetDenial extends RuntimeException {
        private final BudgetReason reason;
        private TestBudgetDenial(BudgetReason reason) {
            super("synthetic execution budget denied: " + reason);
            this.reason = reason;
        }
        private BudgetReason reason() { return reason; }
    }

    @Test
    void budgetDenialStopsSdkRetryBeforeSecondTransportSend() {
        List<String> events = new ArrayList<>();
        AtomicInteger sends = new AtomicInteger();
        HttpClient transport = scriptedTransport(events, sends, 503, FINAL);
        AtomicInteger charges = new AtomicInteger();
        var denial = new TestBudgetDenial(BudgetReason.PROVIDER_ATTEMPT_EXHAUSTED);
        HttpClient charger = new HttpClient() {
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                int current = charges.incrementAndGet();
                events.add("charge-" + current);
                if (current > 1) throw denial;
                return transport.execute(request, options);
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("async not characterized"));
            }
            @Override public void close() { }
        };
        var client = new OpenAIClientImpl(com.openai.core.ClientOptions.builder()
                .apiKey("synthetic-test-key").baseUrl("https://synthetic.invalid/v1")
                .httpClient(charger).maxRetries(1).build());
        var model = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model("synthetic-model").build()).build();

        var failure = assertThrows(RuntimeException.class,
                () -> model.call(new Prompt("synthetic request")));
        assertTrue(causeChainContainsIdentity(failure, denial));
        assertEquals(BudgetReason.PROVIDER_ATTEMPT_EXHAUSTED, denial.reason());
        assertEquals(List.of("charge-1", "send-1", "charge-2"), events);
    }

    private static HttpClient scriptedTransport(List<String> events, AtomicInteger calls, Object... replies) {
        return new HttpClient() {
            @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
                events.add("send-" + calls.incrementAndGet());
                Object reply = replies[calls.get() - 1];
                return reply instanceof Integer status
                        ? new SyntheticResponse(status, "")
                        : new SyntheticResponse(200, (String) reply);
            }
            @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                return CompletableFuture.completedFuture(execute(request, options));
            }
            @Override public void close() { }
        };
    }

    private record SyntheticResponse(int statusCode, String content) implements HttpResponse {
        @Override
        public Headers headers() { return Headers.builder().put("content-type", "application/json").build(); }

        @Override
        public InputStream body() { return new ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

        @Override
        public void close() { }
    }
}
