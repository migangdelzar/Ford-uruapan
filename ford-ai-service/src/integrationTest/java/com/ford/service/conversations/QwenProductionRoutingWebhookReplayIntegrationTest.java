package com.ford.service.conversations;

import com.ford.service.ai.application.ClassificationContext;
import com.ford.service.ai.application.ConversationDecisionService;
import com.ford.service.ai.application.DecisionPolicy;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.CatalogQueryEmbedder;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.outbox.OutboxDeliveryRouter;
import com.ford.service.outbox.OutboxPublisher;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Bounded opt-in local Qwen replay through normal conversation routing and tool policy. */
@EnabledIfEnvironmentVariable(named = "FORD_LOCAL_CATALOG_EXPORT_DIR", matches = ".+")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.modulith.runtime.verification-enabled=false",
        "spring.ai.model.chat=ollama",
        "spring.ai.ollama.base-url=http://localhost:11434",
        "spring.ai.ollama.chat.options.model=${FORD_QWEN_REPLAY_MODEL:qwen3.5:4b}",
        "spring.ai.ollama.chat.options.think-option=false",
        "spring.ai.ollama.chat.options.num-ctx=8192",
        "spring.ai.ollama.chat.options.num-predict=160",
        "ford.ai.local-enabled=true",
        "ford.ai.cloud-enabled=false",
        "ford.ai.local-chat-provider=ollama",
        "ford.ai.local-chat-model-version=${FORD_QWEN_REPLAY_MODEL:qwen3.5:4b}",
        "ford.ai.classification.enabled=false",
        "ford.ai.response-verification-enabled=false",
        "ford.ai.response-repeat-check-enabled=false",
        "ford.channels.meta.delivery.enabled=false",
        "ford.outbox.enabled=false",
        "ford.catalog.tools.enabled=true",
        "ford.conversations.workflow.graph-enabled=true"
})
@Import(LocalCatalogEmbeddingTestConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class QwenProductionRoutingWebhookReplayIntegrationTest {

    private static final String APP_SECRET = "synthetic-production-routing-app-secret";
    private static final String ACCOUNT_ID = "synthetic-production-routing-account";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String SENDER = "synthetic-production-routing-sender-" + RUN_ID;
    private static final String MESSAGE_ID = "wamid.synthetic-production-routing-" + RUN_ID + "-001";
    private static final String BUYER_MESSAGE = "La usaría para ir al trabajo y salir con mi familia. "
            + "Me interesa una pickup fácil de estacionar en ciudad. ¿Qué Ford me convendría revisar primero?";
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(5);
    private static volatile LocalCatalogSnapshotLoader.SnapshotSummary SNAPSHOT;
    private static volatile boolean CATALOG_PREFLIGHT_PASSED;

    @Container
    static final PostgreSQLContainer<?> APP_POSTGRES = new FordPostgresContainer();
    @Container
    static final PostgreSQLContainer<?> CATALOG_POSTGRES = new FordPostgresContainer();
    @Container
    static final LocalBgeOllamaContainer EMBEDDING_OLLAMA = new LocalBgeOllamaContainer();

    @BeforeAll
    static void initializeOnlyDisposableDatabases() throws Exception {
        var appDataSource = new DriverManagerDataSource(
                APP_POSTGRES.getJdbcUrl(), APP_POSTGRES.getUsername(), APP_POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(appDataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        var catalogDataSource = new DriverManagerDataSource(
                CATALOG_POSTGRES.getJdbcUrl(), CATALOG_POSTGRES.getUsername(), CATALOG_POSTGRES.getPassword());
        var exportDirectory = Path.of(System.getenv().getOrDefault("FORD_LOCAL_CATALOG_EXPORT_DIR",
                "build/reports/local-qwen/source-export"));
        LocalCatalogSnapshotLoader.validateApprovedSnapshot(exportDirectory);
        SNAPSHOT = LocalCatalogSnapshotLoader.load(exportDirectory, catalogDataSource);
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", APP_POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", APP_POSTGRES::getUsername);
        registry.add("spring.datasource.password", APP_POSTGRES::getPassword);
        registry.add("ford.catalog.datasource.url", CATALOG_POSTGRES::getJdbcUrl);
        registry.add("ford.catalog.datasource.username", CATALOG_POSTGRES::getUsername);
        registry.add("ford.catalog.datasource.password", CATALOG_POSTGRES::getPassword);
        registry.add("ford.catalog.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("ford.test.catalog-embedding.base-url", EMBEDDING_OLLAMA::baseUrl);
        registry.add("ford.channels.meta.app-secret", () -> APP_SECRET);
    }

    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext applicationContext;
    @Autowired CatalogPort catalogPort;
    @Autowired CatalogRetrievalPort catalogRetrievalPort;
    @Autowired CatalogQueryEmbedder catalogQueryEmbedder;
    @Autowired ConversationDecisionService decisions;
    @Autowired DecisionPolicy decisionPolicy;
    @Autowired ToolAuthorizationPolicy toolAuthorizationPolicy;
    @LocalServerPort int port;

    @Test
    @Order(1)
    void preflightsFullCatalogProductionRouteAndDeliveryOffWithoutCallingQwen() {
        assertThat(SNAPSHOT).isNotNull();
        assertThat(SNAPSHOT.rowCounts()).containsEntry("vehicle-search.csv", 39)
                .containsEntry("vehicle-variants.csv", 121)
                .containsEntry("vehicle-facts.csv", 4887)
                .containsEntry("comparison-facts.csv", 2447)
                .containsEntry("promotions.csv", 10)
                .containsEntry("product-vectors.csv", 1882);
        assertThat(EMBEDDING_OLLAMA.hasBgeModel()).isTrue();

        var embedded = catalogQueryEmbedder.embed("Ford Ranger pickup trabajo familia")
                .orElseThrow(() -> new AssertionError("local BGE-M3 embedding is unavailable"));
        assertThat(embedded.storedModel()).isEqualTo("bge-m3");
        assertThat(embedded.values()).hasSize(1024);
        var page = catalogRetrievalPort.searchVehicles(new VehicleSearchQuery(
                "Ford Ranger pickup trabajo familia", "Ranger", 2027, null, null, null, null, null, 5));
        assertThat(page.semanticSearch()).isEqualTo("ready");
        assertThat(page.results()).anySatisfy(vehicle -> {
            assertThat(vehicle.model()).isEqualToIgnoringCase("Ranger");
            assertThat(vehicle.priceSourceUrl()).contains("forduruapan.mx");
        });
        assertThat(catalogPort.find(new com.ford.service.catalog.api.VehicleLookup("Ranger", 2027,
                "XLT Gasolina 4x2"))).isPresent();

        var decision = decisions.decide(new ClassificationContext(TENANT_ID, CONVERSATION_ID,
                BUYER_MESSAGE, "", "BOT_ACTIVE", false));
        assertThat(decision.intent()).isEqualTo(com.ford.service.ai.application.ConversationIntent.INVENTORY);
        assertThat(decision.toolFamily()).isEqualTo(com.ford.service.ai.application.ToolFamily.VEHICLE_SEARCH);
        assertThat(decisionPolicy.requiresVolatileInformation(decision)).isTrue();
        var availableTools = toolAuthorizationPolicy.availableReadOnlyToolNames();
        assertThat(decisionPolicy.allowedToolNames(decision, availableTools)).contains("search_vehicles");

        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
        CATALOG_PREFLIGHT_PASSED = true;
    }

    @Test
    @Order(2)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_PRODUCTION_ROUTING_REPLAY_ENABLED", matches = "true")
    void commitsOneSyntheticRecommendationTurnWithPersistedSuccessfulSearchEvidence() throws Exception {
        assertThat(CATALOG_PREFLIGHT_PASSED).as("catalog, route, and delivery preflight must pass first").isTrue();
        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
        insertSyntheticChannelBinding();

        var body = """
                {"entry":[{"id":"synthetic-production-routing-waba","changes":[{"value":{"metadata":{"phone_number_id":"%s"},"messages":[{"id":"%s","from":"%s","text":{"body":"%s"}}]}}]}]}
                """.formatted(ACCOUNT_ID, MESSAGE_ID, SENDER, escapeJson(BUYER_MESSAGE));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/meta/whatsapp"))
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature(body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("synthetic webhook acceptance").isEqualTo(202);

        var conversationId = awaitCommittedReply();
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id=:conversation "
                        + "and direction='OUTBOUND' and coalesce(length(content),0)>0")
                .param("conversation", conversationId).query(Integer.class).single()).isEqualTo(1);
        var searchEvidenceCount = jdbc.sql("""
                select count(*)
                  from conversation_turn turn
                  join conversation_decision_event event
                    on event.tenant_id=turn.tenant_id
                   and event.conversation_id=turn.conversation_id
                   and event.receipt_id=turn.receipt_id
                 where turn.tenant_id=:tenant
                   and turn.provider_message_id=:messageId
                   and event.stage='tool'
                   and event.tool_name='search_vehicles'
                   and event.outcome='returned'
                   and coalesce(event.evidence_count, 0) > 0
                """)
                .param("tenant", TENANT_ID).param("messageId", MESSAGE_ID)
                .query(Integer.class).single();
        assertThat(searchEvidenceCount)
                .as("committed recommendation must persist successful synthetic catalog evidence")
                .isGreaterThan(0);
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
    }

    private void insertSyntheticChannelBinding() {
        jdbc.sql("""
                insert into channel_binding
                  (id, webhook_key_hash, tenant_id, channel, provider_account_id, secret_reference,
                   enabled, created_at, updated_at)
                values (:id, null, :tenant, 'WHATSAPP', :account, 'synthetic-production-routing-secret',
                        true, current_timestamp, current_timestamp)
                """)
                .param("id", UUID.randomUUID()).param("tenant", TENANT_ID).param("account", ACCOUNT_ID).update();
    }

    private UUID awaitCommittedReply() throws InterruptedException {
        var deadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var turn = jdbc.sql("""
                    select conversation_id, state, failure_category
                      from conversation_turn
                     where tenant_id=:tenant and provider_message_id=:messageId
                    """)
                    .param("tenant", TENANT_ID).param("messageId", MESSAGE_ID)
                    .query((rs, row) -> new TurnState(rs.getObject("conversation_id", UUID.class),
                            rs.getString("state"), rs.getString("failure_category")))
                    .optional();
            if (turn.isPresent() && "REPLY_COMMITTED".equals(turn.get().state())) {
                return turn.get().conversationId();
            }
            if (turn.isPresent() && !Set.of("ACCEPTED", "PROCESSING", "DEBOUNCED").contains(turn.get().state())) {
                throw new AssertionError("synthetic replay stopped at invalid state " + turn.get().state()
                        + "; failureCategory=" + turn.get().failureCategory());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("synthetic production-routing replay timed out waiting for committed reply");
    }

    private static String signature(String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record TurnState(UUID conversationId, String state, String failureCategory) { }
}
