package com.ford.service.ai.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OpenAiBootCustomizationSeamTest {

    @Test
    void bootModelStreamDisposalClosesSyntheticResponseBody() {
        BlockingStreamingResponseBody body = new BlockingStreamingResponseBody();
        AtomicInteger intercepted = new AtomicInteger();
        AtomicReference<Call> observedCall = new AtomicReference<>();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withBean("intercepted", AtomicInteger.class, () -> intercepted)
                .withBean("streamingBody", BlockingStreamingResponseBody.class, () -> body)
                .withBean("observedCall", AtomicReference.class, () -> observedCall)
                .withUserConfiguration(StreamingInterceptorConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=synthetic-test-key",
                        "spring.ai.openai.base-url=https://synthetic.invalid/v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatModel model = context.getBean(OpenAiChatModel.class);
                    Disposable subscription = model.stream(new Prompt("Reply")).subscribe();
                    try {
                        assertThat(body.awaitReadStarted()).isTrue();
                        subscription.dispose();
                        assertThat(body.awaitClosed()).isTrue();
                        assertThat(observedCall.get()).isNotNull();
                        assertThat(observedCall.get().isCanceled()).isFalse();
                        assertThat(intercepted).hasValue(1);
                    }
                    finally {
                        subscription.dispose();
                        body.releaseRead();
                    }
                });
    }

    @Test
    void bootCreatedModelCarriesDistinctPerCallCustomHeadersToShortCircuitInterceptor() {
        List<String> interceptedTokens = new CopyOnWriteArrayList<>();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withBean("interceptedTokens", List.class, () -> interceptedTokens)
                .withUserConfiguration(RequestContextInterceptorConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=synthetic-test-key",
                        "spring.ai.openai.base-url=https://synthetic.invalid/v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatModel model = context.getBean(OpenAiChatModel.class);

                    for (String token : List.of("fixture-context-alpha", "fixture-context-beta")) {
                        assertThat(model.call(new Prompt("Reply", org.springframework.ai.openai.OpenAiChatOptions.builder()
                                                .customHeaders(Map.of("X-Test-Call-Context", token)).build()))
                                        .getResult().getOutput().getText())
                                .isEqualTo("synthetic answer");
                    }
                    assertThat(interceptedTokens).containsExactly("fixture-context-alpha", "fixture-context-beta");
                });
    }

    @Test
    void bootCreatedModelRetriesRetryableStatusThroughShortCircuitingInterceptor() {
        List<Integer> interceptedStatuses = new CopyOnWriteArrayList<>();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withBean("interceptedStatuses", List.class, () -> interceptedStatuses)
                .withUserConfiguration(RetryingInterceptorConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=synthetic-test-key",
                        "spring.ai.openai.base-url=https://synthetic.invalid/v1",
                        "spring.ai.openai.max-retries=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatModel model = context.getBean(OpenAiChatModel.class);

                    assertThat(model.call(new Prompt("Reply"))
                                    .getResult().getOutput().getText())
                            .isEqualTo("synthetic answer");
                    assertThat(interceptedStatuses).containsExactly(503, 200);
                });
    }

    @Test
    void bootRetryChargesBeforeSyntheticTerminalAndDenialStopsSecondHandoff() {
        RetryChargeTrace trace = new RetryChargeTrace();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withBean("retryChargeTrace", RetryChargeTrace.class, () -> trace)
                .withUserConfiguration(RetryChargeInterceptorConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=synthetic-test-key",
                        "spring.ai.openai.base-url=https://synthetic.invalid/v1",
                        "spring.ai.openai.max-retries=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatModel model = context.getBean(OpenAiChatModel.class);

                    assertThatThrownBy(() -> model.call(new Prompt("Reply")))
                            .satisfies(failure -> assertThat(hasCauseIdentity((Throwable) failure, trace.denial))
                                    .isTrue());
                    assertThat(trace.events).containsExactly("charge-1", "terminal-503", "charge-denied-2");
                    assertThat(trace.terminalInvocations).hasValue(1);
                });
    }

    private static boolean hasCauseIdentity(Throwable failure, Throwable expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause == expected) {
                return true;
            }
        }
        return false;
    }

    static final class RetryChargeTrace {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger chargeAttempts = new AtomicInteger();
        final AtomicInteger terminalInvocations = new AtomicInteger();
        final RuntimeException denial = new IllegalStateException("synthetic budget denied");
    }

    @Configuration(proxyBeanMethods = false)
    static class RetryChargeInterceptorConfiguration {
        @Bean
        OpenAiHttpClientBuilderCustomizer retryChargeCustomizer(RetryChargeTrace trace) {
            return builder -> builder
                    .interceptor(chain -> {
                        int attempt = trace.chargeAttempts.incrementAndGet();
                        if (attempt == 2) {
                            trace.events.add("charge-denied-2");
                            throw trace.denial;
                        }
                        trace.events.add("charge-" + attempt);
                        return chain.proceed(chain.request());
                    })
                    .interceptor(chain -> {
                        trace.terminalInvocations.incrementAndGet();
                        trace.events.add("terminal-503");
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(503)
                                .message("Service Unavailable")
                                .body(ResponseBody.create(
                                        "{\"error\":{\"message\":\"synthetic retry\",\"type\":\"server_error\"}}"
                                                .getBytes(StandardCharsets.UTF_8),
                                        okhttp3.MediaType.get("application/json")))
                                .build();
                    });
        }
    }

    @Test
    void bootCreatedModelUsesCustomizerInterceptorAndReturnsSyntheticCompletion() {
        AtomicInteger intercepted = new AtomicInteger();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withBean("intercepted", AtomicInteger.class, () -> intercepted)
                .withUserConfiguration(InterceptorConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=synthetic-test-key",
                        "spring.ai.openai.base-url=https://synthetic.invalid/v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatModel model = context.getBean(OpenAiChatModel.class);

                    assertThat(model.call(new Prompt("Reply"))
                                    .getResult().getOutput().getText())
                            .isEqualTo("synthetic answer");
                    assertThat(intercepted).hasValue(1);
                });
    }

    @Test
    void bootAutoConfigurationCustomizesSyncAndAsyncOpenAiClients() {
        AtomicInteger customizations = new AtomicInteger();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withBean("customizations", AtomicInteger.class, () -> customizations)
                .withUserConfiguration(CustomizerConfiguration.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=synthetic-test-key",
                        "spring.ai.openai.base-url=https://synthetic.invalid/v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenAiChatModel.class);
                    assertThat(customizations).hasValue(2);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfiguration {
        @Bean
        OpenAiHttpClientBuilderCustomizer recordingCustomizer(AtomicInteger customizations) {
            return builder -> customizations.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InterceptorConfiguration {
        @Bean
        OpenAiHttpClientBuilderCustomizer shortCircuitingCustomizer(AtomicInteger intercepted) {
            return builder -> builder.interceptor(chain -> {
                intercepted.incrementAndGet();
                return new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(
                                """
                                {"id":"synthetic","object":"chat.completion","created":0,
                                 "model":"synthetic","choices":[{"index":0,"message":{"role":"assistant","content":"synthetic answer"},"finish_reason":"stop"}]}
                                """.getBytes(StandardCharsets.UTF_8),
                                okhttp3.MediaType.get("application/json")))
                        .build();
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequestContextInterceptorConfiguration {
        @Bean
        OpenAiHttpClientBuilderCustomizer requestContextShortCircuitingCustomizer(List<String> interceptedTokens) {
            return builder -> builder.interceptor(chain -> {
                interceptedTokens.add(chain.request().header("X-Test-Call-Context"));
                return new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(
                                """
                                {"id":"synthetic","object":"chat.completion","created":0,
                                 "model":"synthetic","choices":[{"index":0,"message":{"role":"assistant","content":"synthetic answer"},"finish_reason":"stop"}]}
                                """.getBytes(StandardCharsets.UTF_8),
                                okhttp3.MediaType.get("application/json")))
                        .build();
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RetryingInterceptorConfiguration {
        @Bean
        OpenAiHttpClientBuilderCustomizer retryingShortCircuitingCustomizer(List<Integer> interceptedStatuses) {
            return builder -> builder.interceptor(chain -> {
                int code = interceptedStatuses.isEmpty() ? 503 : 200;
                interceptedStatuses.add(code);
                String body = code == 503
                        ? "{\"error\":{\"message\":\"synthetic retry\",\"type\":\"server_error\"}}"
                        : "{\"id\":\"synthetic\",\"object\":\"chat.completion\",\"created\":0,"
                                + "\"model\":\"synthetic\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic answer\"},\"finish_reason\":\"stop\"}]}";
                return new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(code == 503 ? "Service Unavailable" : "OK")
                        .body(ResponseBody.create(body.getBytes(StandardCharsets.UTF_8),
                                okhttp3.MediaType.get("application/json")))
                        .build();
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StreamingInterceptorConfiguration {
        @Bean
        OpenAiHttpClientBuilderCustomizer streamingShortCircuitingCustomizer(
                BlockingStreamingResponseBody body, AtomicInteger intercepted, AtomicReference<Call> observedCall) {
            return builder -> builder.interceptor(chain -> {
                intercepted.incrementAndGet();
                observedCall.set(chain.call());
                return new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/event-stream")
                        .body(body)
                        .build();
            });
        }
    }

    static final class BlockingStreamingResponseBody extends ResponseBody {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final BufferedSource source = Okio.buffer(new Source() {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                readStarted.countDown();
                try {
                    releaseRead.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Synthetic stream read interrupted", exception);
                }
                return -1;
            }

            @Override
            public Timeout timeout() {
                return Timeout.NONE;
            }

            @Override
            public void close() {
                closed.countDown();
                releaseRead.countDown();
            }
        });

        @Override
        public okhttp3.MediaType contentType() {
            return okhttp3.MediaType.get("text/event-stream");
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public BufferedSource source() {
            return source;
        }

        boolean awaitReadStarted() {
            return await(readStarted);
        }

        boolean awaitClosed() {
            return await(closed);
        }

        void releaseRead() {
            releaseRead.countDown();
        }

        private static boolean await(CountDownLatch latch) {
            try {
                return latch.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
