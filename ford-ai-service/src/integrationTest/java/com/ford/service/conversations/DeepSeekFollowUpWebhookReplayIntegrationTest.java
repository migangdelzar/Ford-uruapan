package com.ford.service.conversations;

import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.ai.configuration.ChatProviderProperties;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.outbox.OutboxDeliveryRouter;
import com.ford.service.outbox.OutboxPublisher;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in synthetic webhook replay using DeepSeek only; never used by production configuration. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
         "spring.modulith.runtime.verification-enabled=false",
         "spring.ai.model.chat=openai",
         "spring.ai.openai.api-key=${DEEPSEEK_API_KEY:}",
         "spring.ai.openai.base-url=https://api.deepseek.com",
         "spring.ai.openai.chat.options.model=${FORD_AI_CLOUD_MODEL_VERSION:deepseek-flash}",
         "spring.ai.openai.chat.options.max-tokens=${FORD_DEEPSEEK_REPLAY_MAX_TOKENS:500}",
        "ford.ai.cloud-chat-provider=openai",
        "ford.ai.cloud-chat-model-version=${FORD_AI_CLOUD_MODEL_VERSION:deepseek-flash}",
        "ford.ai.cloud-enabled=true",
        "ford.ai.local-enabled=false",
         "ford.ai.provider-max-attempts=1",
         "ford.ai.log-provider-errors=true",
        "ford.ai.providers.fallback-order=",
        "ford.ai.classification.enabled=false",
        "ford.ai.response-verification-enabled=true",
        "ford.channels.meta.delivery.enabled=false",
        "ford.outbox.enabled=false",
        "ford.catalog.tools.enabled=true",
        "ford.conversations.workflow.graph-enabled=true"
})
@Import({LocalCatalogEmbeddingTestConfiguration.class})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@EnabledIfEnvironmentVariable(named = "FORD_DEEPSEEK_REPLAY_ENABLED", matches = "true")
class DeepSeekFollowUpWebhookReplayIntegrationTest {
    private static final String APP_SECRET = "synthetic-deepseek-app-secret";
    private static final String ACCOUNT_ID = "synthetic-deepseek-account";
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String SENDER = "synthetic-deepseek-sender-" + RUN_ID;
    private static final String FIRST_MESSAGE_ID = "wamid.synthetic-deepseek-" + RUN_ID + "-001";
    private static final String SECOND_MESSAGE_ID = "wamid.synthetic-deepseek-" + RUN_ID + "-002";
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(5);
    private static volatile boolean PREFLIGHT_PASSED;
    private static volatile boolean TWO_TURN_PASSED;
    private static volatile boolean TEN_TURN_PASSED;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    @Container
    static final PostgreSQLContainer<?> CATALOG_POSTGRES = new FordPostgresContainer();
    @Container
    static final LocalBgeOllamaContainer EMBEDDING_OLLAMA = new LocalBgeOllamaContainer();

    @BeforeAll
    static void migrateDisposableDatabase() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        seedTenantFinanceFixture(dataSource);
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ford.catalog.datasource.url", CATALOG_POSTGRES::getJdbcUrl);
        registry.add("ford.catalog.datasource.username", CATALOG_POSTGRES::getUsername);
        registry.add("ford.catalog.datasource.password", CATALOG_POSTGRES::getPassword);
        registry.add("ford.catalog.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("ford.test.catalog-embedding.base-url", EMBEDDING_OLLAMA::baseUrl);
        registry.add("ford.channels.meta.app-secret", () -> APP_SECRET);
    }

    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext applicationContext;
    @Autowired Environment environment;
    @Autowired CatalogPort catalogPort;
    @Autowired FinanceTermsRepository financeTermsRepository;
    @Autowired AiProperties aiProperties;
    @Autowired ChatProviderProperties chatProviderProperties;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @LocalServerPort int port;

    @Test
    @Order(0)
    void preflightsDeepSeekAndSyntheticResourcesBeforeAnyWebhook() throws Exception {
        var apiKey = environment.getProperty("spring.ai.openai.api-key", "");
        assertThat(apiKey != null && !apiKey.isBlank())
                .as("DEEPSEEK_API_KEY must be present; credential value is never logged")
                .isTrue();
        assertThat(aiProperties.cloudChatProvider()).isEqualTo("openai");
        assertThat(aiProperties.cloudChatModelVersion().toLowerCase(Locale.ROOT)).startsWith("deepseek-");
        assertThat(aiProperties.cloudEnabled()).isTrue();
        assertThat(aiProperties.localEnabled()).isFalse();
        assertThat(chatProviderProperties.fallbackOrder()).isEmpty();
        assertThat(chatProviderProperties.eligibleFallbackIds()).isEmpty();
        var configuredBaseUrl = URI.create(environment.getRequiredProperty("spring.ai.openai.base-url"));
        assertThat(configuredBaseUrl).isEqualTo(URI.create("https://api.deepseek.com"));
        assertDeliveryDisabled();

        var exportDirectory = Path.of(System.getenv().getOrDefault("FORD_LOCAL_CATALOG_EXPORT_DIR",
                "build/reports/local-qwen/source-export"));
        var catalogDataSource = new DriverManagerDataSource(
                CATALOG_POSTGRES.getJdbcUrl(), CATALOG_POSTGRES.getUsername(), CATALOG_POSTGRES.getPassword());
        var snapshot = LocalCatalogSnapshotLoader.load(exportDirectory, catalogDataSource);
        assertThat(snapshot.rowCounts()).containsEntry("vehicle-search.csv", 39)
                .containsEntry("vehicle-variants.csv", 121)
                .containsEntry("promotions.csv", 10)
                .containsEntry("product-vectors.csv", 299);
        assertThat(EMBEDDING_OLLAMA.hasBgeModel()).isTrue();
        assertScenarioFixturesAreAvailable();
        PREFLIGHT_PASSED = true;
    }

    @Test
    @Order(1)
    @EnabledIfEnvironmentVariable(named = "FORD_DEEPSEEK_2_TURN_REPLAY_ENABLED", matches = "true")
    void acceptsAndCommitsTwoSequentialSyntheticFollowUpTurns() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "DeepSeek/catalog preflight did not pass; no webhook sent");
        assertDeliveryDisabled();
        insertSyntheticBinding();
        var transcriptRunId = "deepseek-2-turn-" + UUID.randomUUID();
        var transcript = new DeepSeekReplayTranscriptWriter(Path.of("build/reports"),
                transcriptRunId, 2);
        var firstTurn = new NaturalBuyerConversationScenario.Turn(1,
                "Me interesa la Ranger 2027 XLT Gasolina 4x2. ¿Cuál es su precio publicado?",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT);
        var secondTurn = new NaturalBuyerConversationScenario.Turn(2,
                "¿Y de esa versión, qué motor tiene?",
                NaturalBuyerConversationScenario.Expectation.CATALOG_FACT);
        postSyntheticMessage(FIRST_MESSAGE_ID, firstTurn.message());
        var firstConversation = awaitCommittedReply(FIRST_MESSAGE_ID);
        var firstReply = latestOutbound(firstConversation);
        var firstTools = persistedToolEvents(FIRST_MESSAGE_ID);
        transcript.append(firstTurn, firstReply, firstTools);
        postSyntheticMessage(SECOND_MESSAGE_ID, secondTurn.message());
        var secondConversation = awaitCommittedReply(SECOND_MESSAGE_ID);
        var secondReply = latestOutbound(secondConversation);
        var secondTools = persistedToolEvents(SECOND_MESSAGE_ID);
        transcript.append(secondTurn, secondReply, secondTools);

        assertThat(firstConversation).isEqualTo(secondConversation);
        assertThat(firstReply).isNotBlank();
        assertThat(firstReply).as("first reply must use the exact Ranger variant price")
                .contains("907")
                .doesNotContain("No pude confirmar un precio exacto")
                .doesNotContain("Qué año y versión");
        assertThat(secondReply).isNotBlank();
        assertThat(secondReply.toLowerCase(Locale.ROOT))
                .as("second reply must answer the engine follow-up with evidence")
                .containsAnyOf("motor", "2.3", "ecoboost", "litros", "cilindros");
        assertThat(countOutbound(firstConversation)).isEqualTo(2);
        assertThat(secondTools).isNotEmpty();
        assertThat(secondTools).anySatisfy(tool ->
                assertThat(tool.evidenceCount()).isNotNull().isPositive());
        assertThat(secondTools).noneMatch(tool -> "failed".equals(tool.outcome()));
        transcript.complete();
        System.out.println("DeepSeek transcript written: " + transcript.path());
        TWO_TURN_PASSED = true;
    }

    @Test
    @Order(2)
    @EnabledIfEnvironmentVariable(named = "FORD_DEEPSEEK_TURN3_DIAGNOSTIC_ENABLED", matches = "true")
    void diagnosesOnlyThreeSyntheticTurnsAfterTwoTurnGatePassed() throws Exception {
        Assumptions.assumeTrue(TWO_TURN_PASSED, "2-turn DeepSeek gate did not pass; no diagnostic replay sent");
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "DeepSeek/catalog preflight did not pass; no webhook sent");
        runScenarioStage(3, "deepseek-turn3-diagnostic-" + UUID.randomUUID());
    }

    @Test
    @Order(3)
    @EnabledIfEnvironmentVariable(named = "FORD_DEEPSEEK_10_TURN_REPLAY_ENABLED", matches = "true")
    void acceptsTenSyntheticTurnsOnlyAfterTwoTurnGatePassed() throws Exception {
        Assumptions.assumeFalse(turn3DiagnosticEnabled(), "turn-3 diagnostic mode excludes the 10-turn stage");
        Assumptions.assumeTrue(TWO_TURN_PASSED, "2-turn DeepSeek gate did not pass; no 10-turn replay sent");
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "DeepSeek/catalog preflight did not pass; no webhook sent");
        runScenarioStage(10, "deepseek-10-" + UUID.randomUUID());
        TEN_TURN_PASSED = true;
    }

    @Test
    @Order(4)
    @EnabledIfEnvironmentVariable(named = "FORD_DEEPSEEK_100_TURN_REPLAY_ENABLED", matches = "true")
    void acceptsOneHundredSyntheticTurnsOnlyAfterTenTurnGatePassed() throws Exception {
        Assumptions.assumeFalse(turn3DiagnosticEnabled(), "turn-3 diagnostic mode excludes the 100-turn stage");
        Assumptions.assumeTrue(TEN_TURN_PASSED, "10-turn DeepSeek gate did not pass; no 100-turn replay sent");
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "DeepSeek/catalog preflight did not pass; no webhook sent");
        runScenarioStage(100, "deepseek-100-" + UUID.randomUUID());
    }

    private static boolean turn3DiagnosticEnabled() {
        return "true".equalsIgnoreCase(System.getenv("FORD_DEEPSEEK_TURN3_DIAGNOSTIC_ENABLED"));
    }

    private void runScenarioStage(int expectedTurns, String stageRunId) throws Exception {
        assertDeliveryDisabled();
        insertSyntheticBinding();
        var turns = NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper())
                .subList(0, expectedTurns);
        var sender = "synthetic-deepseek-" + stageRunId;
        var transcript = new DeepSeekReplayTranscriptWriter(Path.of("build/reports"), stageRunId, expectedTurns);
        var committedTurns = 0;
        var toolEventCount = 0;
        var positiveToolEvidence = false;
        var failedToolNames = new java.util.ArrayList<String>();
        UUID conversationId = null;

        for (var turn : turns) {
            var messageId = "wamid." + stageRunId + "-" + String.format(Locale.ROOT, "%03d", turn.number());
            try {
                postSyntheticMessage(messageId, turn.message(), sender);
                var turnConversation = awaitCommittedReply(messageId);
                if (conversationId == null) conversationId = turnConversation;
                if (!conversationId.equals(turnConversation)) {
                    throw new AssertionError("synthetic follow-up changed conversation");
                }
                var turnTools = persistedToolEvents(messageId);
                transcript.append(turn, latestOutbound(turnConversation), turnTools);
                committedTurns++;
                toolEventCount += turnTools.size();
                for (var tool : turnTools) {
                    if ("failed".equals(tool.outcome())) failedToolNames.add(tool.toolName());
                    if (tool.evidenceCount() != null && tool.evidenceCount() > 0) {
                        positiveToolEvidence = true;
                    }
                }
            } catch (Exception | AssertionError failure) {
                var turnTools = persistedToolEvents(messageId);
                var failureCategory = failure instanceof AssertionError
                        && failure.getMessage() != null && failure.getMessage().contains("timed out")
                        ? DeepSeekReplayTranscriptWriter.FailureCategory.TURN_TIMEOUT
                        : persistedFailureCategory(messageId)
                                .orElse(DeepSeekReplayTranscriptWriter.FailureCategory.INVALID_STATE);
                transcript.recordFailure(turn, failureCategory, turnTools);
                throw new AssertionError("DeepSeek replay stopped at turn " + turn.number()
                        + " of " + expectedTurns + "; committed=" + committedTurns
                        + "; safe category=" + failureCategory + "; transcript=" + transcript.path(), failure);
            }
        }
        assertThat(committedTurns).isEqualTo(expectedTurns);
        assertThat(countOutbound(conversationId)).isEqualTo(expectedTurns);
        if (toolEventCount == 0) {
            throw new AssertionError("DeepSeek replay produced no persisted tool trace; transcript=" + transcript.path());
        }
        assertThat(failedToolNames)
                .as("DeepSeek replay must not commit turns with failed tool outcomes; transcript=" + transcript.path())
                .isEmpty();
        assertThat(positiveToolEvidence)
                .as("DeepSeek replay must include at least one positive tool-evidence item; transcript="
                        + transcript.path())
                .isTrue();
        transcript.complete();
        System.out.println("DeepSeek transcript written: " + transcript.path());
    }

    private java.util.Optional<DeepSeekReplayTranscriptWriter.FailureCategory> persistedFailureCategory(String messageId) {
        return jdbc.sql("select failure_category from conversation_turn where tenant_id=:tenant and provider_message_id=:messageId")
                .param("tenant", TENANT_ID).param("messageId", messageId)
                .query(String.class).optional()
                .flatMap(value -> switch (value) {
                    case "assistant_unavailable_retry" -> java.util.Optional.of(
                            DeepSeekReplayTranscriptWriter.FailureCategory.ASSISTANT_UNAVAILABLE_RETRY);
                    case "assistant_unavailable_review" -> java.util.Optional.of(
                            DeepSeekReplayTranscriptWriter.FailureCategory.ASSISTANT_UNAVAILABLE_REVIEW);
                    case "database_failure" -> java.util.Optional.of(
                            DeepSeekReplayTranscriptWriter.FailureCategory.DATABASE_FAILURE);
                    case "graph_failure" -> java.util.Optional.of(
                            DeepSeekReplayTranscriptWriter.FailureCategory.GRAPH_FAILURE);
                    default -> java.util.Optional.empty();
                });
    }

    private List<DeepSeekReplayTranscriptWriter.ToolTrace> persistedToolEvents(String providerMessageId) {
        return jdbc.sql("""
                select event.tool_name, event.outcome, event.evidence_count
                  from conversation_turn turn
                  join conversation_decision_event event
                    on event.tenant_id=turn.tenant_id
                   and event.conversation_id=turn.conversation_id
                   and event.receipt_id=turn.receipt_id
                 where turn.tenant_id=:tenant
                   and turn.provider_message_id=:messageId
                   and event.stage='tool'
                   and event.outcome in ('returned','failed','empty')
                   and event.tool_name is not null
                 order by event.event_sequence
                """)
                .param("tenant", TENANT_ID).param("messageId", providerMessageId)
                .query((rs, row) -> new DeepSeekReplayTranscriptWriter.ToolTrace(rs.getString("tool_name"),
                        rs.getString("outcome"), (Integer) rs.getObject("evidence_count")))
                .list();
    }

    private void assertDeliveryDisabled() {
        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
    }

    private void assertScenarioFixturesAreAvailable() {
        assertThat(catalogPort.find(new VehicleLookup("Ranger", 2027, "XLT Gasolina 4x2")))
                .hasValueSatisfying(vehicle -> {
                    assertThat(vehicle.price()).isEqualByComparingTo("907000.00");
                    assertThat(vehicle.priceSourceUrl()).isNotBlank();
                    assertThat(vehicle.observedAt()).isNotNull();
                });
        var terms = financeTermsRepository.findActive(TENANT_ID, "SYNTHETIC_FIXED_60", 60).orElseThrow();
        assertThat(terms.annualRate()).isEqualByComparingTo("0.11250");
        assertThat(financeTermsRepository.findActive(UUID.randomUUID(), "SYNTHETIC_FIXED_60", 60)).isEmpty();
    }

    private void postSyntheticMessage(String messageId, String text) throws Exception {
        postSyntheticMessage(messageId, text, SENDER);
    }

    private void postSyntheticMessage(String messageId, String text, String sender) throws Exception {
        var encodedText = objectMapper.writeValueAsString(text);
        var body = """
                {"entry":[{"id":"synthetic-deepseek-waba","changes":[{"value":{"metadata":{"phone_number_id":"%s"},"messages":[{"id":"%s","from":"%s","text":{"body":%s}}]}}]}]}
                """.formatted(ACCOUNT_ID, messageId, sender, encodedText);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/meta/whatsapp"))
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature(body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("synthetic webhook acceptance").isEqualTo(202);
    }

    private UUID awaitCommittedReply(String providerMessageId) throws InterruptedException {
        var deadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var turn = jdbc.sql("""
                    select conversation_id, state, failure_category
                      from conversation_turn
                     where tenant_id=:tenant and provider_message_id=:messageId
                    """)
                    .param("tenant", TENANT_ID).param("messageId", providerMessageId)
                    .query((rs, row) -> new TurnRow(rs.getObject("conversation_id", UUID.class),
                            rs.getString("state"), rs.getString("failure_category")))
                    .optional();
            if (turn.isPresent() && "REPLY_COMMITTED".equals(turn.get().state())
                    && countOutbound(turn.get().conversationId()) > 0) {
                return turn.get().conversationId();
            }
            if (turn.isPresent() && !java.util.Set.of("ACCEPTED", "PROCESSING", "DEBOUNCED")
                    .contains(turn.get().state())) {
                throw new AssertionError("DeepSeek replay stopped at safe state=" + turn.get().state()
                        + "; category=" + turn.get().failureCategory());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("DeepSeek replay reply timed out");
    }

    private int countOutbound(UUID conversationId) {
        return jdbc.sql("select count(*) from conversation_message where conversation_id=:conversation "
                        + "and direction='OUTBOUND' and coalesce(length(content),0)>0")
                .param("conversation", conversationId).query(Integer.class).single();
    }

    private String latestOutbound(UUID conversationId) {
        return jdbc.sql("select content from conversation_message where conversation_id=:conversation "
                        + "and direction='OUTBOUND' and coalesce(length(content),0)>0 "
                        + "order by received_at desc, id desc limit 1")
                .param("conversation", conversationId).query(String.class).single();
    }

    private void insertSyntheticBinding() {
        jdbc.sql("""
                insert into channel_binding
                  (id, webhook_key_hash, tenant_id, channel, provider_account_id, secret_reference,
                   enabled, created_at, updated_at)
                select :id, null, :tenant, 'WHATSAPP', :account, 'synthetic-deepseek-secret', true,
                       current_timestamp, current_timestamp
                 where not exists (select 1 from channel_binding where tenant_id=:tenant
                                   and channel='WHATSAPP' and provider_account_id=:account)
                """)
                .param("id", UUID.randomUUID()).param("tenant", TENANT_ID).param("account", ACCOUNT_ID).update();
    }

    private static void seedTenantFinanceFixture(DriverManagerDataSource dataSource) {
        var finance = JdbcClient.create(dataSource);
        var productId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        finance.sql("""
                insert into finance_product
                  (id, tenant_id, lender_code, product_code, name, annual_rate, opening_fee, insurance_fee,
                   effective_from, effective_to, requirements, source_system)
                values (:id, :tenant, 'SYNTHETIC_LENDER', 'SYNTHETIC_FIXED_60', 'Synthetic fixed fixture',
                        0.12500, 1500.00, 750.00, current_timestamp - interval '1 day',
                        current_timestamp + interval '30 days', '[]'::jsonb, 'synthetic-fixture')
                """)
                .param("id", productId).param("tenant", TENANT_ID).update();
        finance.sql("insert into finance_term (id, finance_product_id, months, annual_rate) "
                        + "values (:id, :product, 60, 0.11250), (:id48, :product, 48, 0.13000)")
                .param("id", UUID.fromString("88888888-8888-4888-8888-888888888888"))
                .param("id48", UUID.fromString("99999999-9999-4999-8999-999999999999"))
                .param("product", productId).update();
    }

    private static String signature(String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record TurnRow(UUID conversationId, String state, String failureCategory) { }
}
