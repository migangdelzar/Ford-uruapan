package com.ford.service.conversations;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.catalog.api.CatalogPort;
import com.ford.service.catalog.api.OfferQuery;
import com.ford.service.catalog.api.PromotionQuery;
import com.ford.service.catalog.api.CatalogRetrievalPort;
import com.ford.service.catalog.api.CatalogQueryEmbedder;
import com.ford.service.catalog.api.VehicleLookup;
import com.ford.service.catalog.api.VehicleSearchQuery;
import com.ford.service.channels.domain.ReplyOption;
import com.ford.service.finance.application.port.out.FinanceTermsRepository;
import com.ford.service.ai.application.CatalogNameIndexService;
import com.ford.service.ai.application.DeterministicCatalogAnswerService;
import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.ToolAuthorizationPolicy;
import com.ford.service.ai.application.AssistantService;
import com.ford.service.ai.application.AiExecutionContext;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.outbox.OutboxDeliveryRouter;
import com.ford.service.outbox.OutboxPublisher;
import com.ford.service.channels.application.port.out.ChannelDeliveryPort;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real HTTP + local Ollama replay. Uses a disposable PostgreSQL database and synthetic webhooks only. */
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
        "ford.ai.log-provider-errors=true",
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
@Import({LocalCatalogEmbeddingTestConfiguration.class, LocalQwenCallMeasurementConfiguration.class,
        LocalQwenComparisonPrefetchConfiguration.class, LocalQwenReplayAssistantConfiguration.class})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "FORD_QWEN_REPLAY_ENABLED", matches = "true")
class QwenTwoTurnWebhookReplayIntegrationTest {
    private static final String APP_SECRET = "synthetic-qwen-app-secret";
    private static final String ACCOUNT_ID = "synthetic-qwen-account";
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String SENDER = "synthetic-qwen-sender-" + RUN_ID;
    private static final String FIRST_MESSAGE_ID = "wamid.synthetic-qwen-" + RUN_ID + "-001";
    private static final String SECOND_MESSAGE_ID = "wamid.synthetic-qwen-" + RUN_ID + "-002";
    private static final java.time.Duration REPLAY_TURN_TIMEOUT = java.time.Duration.ofMinutes(5);
    private static volatile boolean PREFLIGHT_PASSED;
    private static volatile LocalCatalogSnapshotLoader.SnapshotSummary SNAPSHOT_SUMMARY;
    private static volatile LocalCatalogSnapshotSelection.Label SNAPSHOT_LABEL;

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
        registry.add("ford.test.local-qwen-comparison-prefetch.enabled",
                () -> "true".equalsIgnoreCase(System.getenv("FORD_QWEN_COMPARISON_PREFETCH_ENABLED")));
        registry.add("ford.channels.meta.app-secret", () -> APP_SECRET);
    }

    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext applicationContext;
    @Autowired CatalogPort catalogPort;
    @Autowired CatalogRetrievalPort catalogRetrievalPort;
    @Autowired CatalogQueryEmbedder catalogQueryEmbedder;
    @Autowired FinanceTermsRepository financeTermsRepository;
    @Autowired SystemPromptService systemPromptService;
    @Autowired ToolAuthorizationPolicy toolAuthorizationPolicy;
    @Autowired AssistantService assistantService;
    @Autowired LocalQwenCallMeasurementCollector providerCallMeasurements;
    @Autowired(required = false) LocalReplayPrecisionEvidence replayPrecisionEvidence;
    @Autowired MemoryProperties memoryProperties;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @LocalServerPort int port;

    @Test
    @Order(0)
    void preflightsLocalCatalogSnapshotAndVectorRetrieval() throws Exception {
        var selection = LocalCatalogSnapshotSelection.fromEnvironment(System.getenv());
        var catalogDataSource = new DriverManagerDataSource(
                CATALOG_POSTGRES.getJdbcUrl(), CATALOG_POSTGRES.getUsername(), CATALOG_POSTGRES.getPassword());
        var snapshot = LocalCatalogSnapshotLoader.load(selection.exportDirectory(), catalogDataSource);
        assertSnapshotCounts(snapshot, selection.label());

        assertThat(EMBEDDING_OLLAMA.hasBgeModel()).isTrue();
        var queryVector = catalogQueryEmbedder.embed("pickup familiar Ranger") .orElseThrow();
        assertThat(queryVector.storedModel()).isEqualTo("bge-m3");
        assertThat(queryVector.values()).hasSize(1024);

        var vehiclePage = catalogRetrievalPort.searchVehicles(new VehicleSearchQuery(
                "pickup familiar Ranger", "RANGER", 2027, null, null, null, null, null, 5));
        assertThat(vehiclePage.semanticSearch()).isEqualTo("ready");
        assertThat(vehiclePage.results()).anySatisfy(vehicle -> {
            assertThat(vehicle.model()).isEqualToIgnoringCase("RANGER");
            assertThat(vehicle.year()).isEqualTo(2027);
            assertThat(vehicle.priceSourceUrl()).isNotBlank();
            assertThat(vehicle.observedAt()).isNotNull();
        });

        var rangerXlt = catalogPort.find(new VehicleLookup("Ranger", 2027, "XLT Gasolina 4x2")).orElseThrow();
        assertThat(rangerXlt.model()).isEqualToIgnoringCase("Ranger");
        assertThat(rangerXlt.facts()).isNotEmpty();
        var comparisonPrefetcher = new LocalReplayComparisonPrefetcher(new LocalComparisonPairResolver(),
                catalogPort::nameIndexEntries, new com.ford.service.ai.adapter.in.tool.CatalogTools(
                catalogPort, catalogRetrievalPort));
        var prefetchedComparison = comparisonPrefetcher.prefetch(
                "¿Qué dato no está disponible para hacer una comparación completa?",
                "[HISTORIAL DE CONVERSACIÓN]\n"
                        + "- usuario: Compara Ranger 2027 XL Gasolina 4x2 con XLT Gasolina 4x2.\n"
                        + "[/HISTORIAL DE CONVERSACIÓN]")
                .orElseThrow(() -> new AssertionError("exact prior buyer pair did not prefetch comparison evidence"));
        assertThat(objectMapper.writeValueAsString(prefetchedComparison)).hasSizeLessThanOrEqualTo(16_000);

        var asOf = LocalDate.now(ZoneOffset.UTC);
        assertThat(catalogPort.promotions(new PromotionQuery("RANGER", 2027, asOf, false, 20)).results())
                .isNotEmpty().allSatisfy(promotion -> {
                    assertThat(promotion.sourceUrl()).isNotBlank();
                    assertThat(promotion.observedAt()).isNotNull();
                });
        var offerPage = catalogRetrievalPort.searchOffers(new OfferQuery("promoción Ranger", "RANGER", 2027,
                "promotion", null, null, null, null, null, null, false, asOf, 5));
        assertThat(offerPage.semanticSearch()).isEqualTo("ready");
        assertThat(offerPage.results()).isNotEmpty().allSatisfy(offer -> {
            assertThat(offer.offerId()).isNotBlank();
            assertThat(offer.sourceUrl()).isNotBlank();
            assertThat(offer.observedAt()).isNotNull();
        });
        assertThat(catalogPort.status().latestSyncStatus()).isEqualTo("LOCAL_SNAPSHOT");
        var knownPageUrl = vehiclePage.results().stream()
                .filter(vehicle -> "RANGER".equalsIgnoreCase(vehicle.model()))
                .map(com.ford.service.catalog.api.VehicleEvidence::modelPageUrl)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        assertThat(catalogPort.pageLinks(knownPageUrl, 0, 10, false))
                .satisfies(page -> {
                    assertThat(page.knownSource()).isTrue();
                    assertThat(page.links()).isEmpty();
                });
        if (comparisonPrefetchEnabled()) {
            var exactComparisonMessage = "De las dos versiones que acabamos de comparar, Ranger 2027 XL Gasolina 4x2 "
                    + "y Ranger 2027 XLT Gasolina 4x2, ¿cuál tiene el precio publicado menor?";
            assertThat(applicationContext.getBean(LocalReplayComparisonPrefetcher.class)
                    .prefetch(exactComparisonMessage))
                    .as("prefetch must resolve the exact synthetic Ranger comparison from the loaded snapshot")
                    .isPresent();
            var promotionHistory = "[HISTORIAL DE CONVERSACIÓN]\n"
                    + "- usuario: Empecemos por la Ranger 2027 XLT Gasolina 4x2 del catálogo, "
                    + "¿cuál es su precio publicado?\n[FIN DEL HISTORIAL]";
            assertThat(catalogPort.nameIndexEntries())
                    .as("catalog identity index has the exact XLT trim")
                    .contains(new com.ford.service.catalog.api.VehicleNameIndexEntry(
                            "RANGER", 2027, "XLT Gasolina 4x2"));
            assertThat(new LocalBuyerVehicleResolver().resolve(
                    "¿Ese monto corresponde a la XLT que acabamos de revisar?", promotionHistory,
                    catalogPort.nameIndexEntries()))
                    .as("exact buyer history resolves one Ranger trim identity")
                    .contains(new LocalBuyerVehicleResolver.VehicleRef("RANGER", 2027, "XLT GASOLINA 4X2"));
            assertThat(new com.ford.service.ai.adapter.in.tool.CatalogTools(catalogPort, catalogRetrievalPort)
                    .listPromotions("RANGER", 2027, null, false, 0, 20, "full", "RANGER 2027").results())
                    .as("catalog tool returns exact model/year promotion evidence")
                    .isNotEmpty();
            var directPrefetcher = new LocalReplayPromotionPrefetcher(new LocalBuyerVehicleResolver(),
                    catalogPort::nameIndexEntries,
                    new com.ford.service.ai.adapter.in.tool.CatalogTools(catalogPort, catalogRetrievalPort));
            assertThat(directPrefetcher.prefetch(
                    "¿Ese monto corresponde a la XLT que acabamos de revisar?", promotionHistory))
                    .as("promotion prefetch succeeds with the exact live catalog index and tool")
                    .isPresent();
            assertThat(applicationContext.getBean(LocalReplayPromotionPrefetcher.class)
                    .prefetch("¿Ese monto corresponde a la XLT que acabamos de revisar?", promotionHistory))
                    .as("prefetch must resolve exact promotion evidence for the natural replay follow-up")
                    .isPresent();
        }
        assertAllCatalogSpringAiCallbacks(knownPageUrl, catalogDataSource);
        SNAPSHOT_SUMMARY = snapshot;
        SNAPSHOT_LABEL = selection.label();
        PREFLIGHT_PASSED = true;
    }

    private static void assertSnapshotCounts(LocalCatalogSnapshotLoader.SnapshotSummary snapshot,
                                             LocalCatalogSnapshotSelection.Label label) {
        var counts = snapshot.rowCounts();
        assertThat(counts).containsEntry("vehicle-search.csv", 39)
                .containsEntry("vehicle-variants.csv", 121)
                .containsEntry("comparison-facts.csv", 2447)
                .containsEntry("promotions.csv", 10);
        switch (label) {
            case OCT22_BASELINE -> assertThat(counts).containsEntry("vehicle-facts.csv", 4881)
                    .containsEntry("product-vectors.csv", 299);
            case OCT25_CANDIDATE -> assertThat(counts).containsEntry("vehicle-facts.csv", 4887)
                    .containsEntry("product-vectors.csv", 1882);
        }
    }

    private void assertAllCatalogSpringAiCallbacks(String knownPageUrl,
                                                    DriverManagerDataSource catalogDataSource) throws Exception {
        var catalogNames = Set.of("search_vehicles", "get_vehicle", "search_offers",
                "search_vehicle_and_offers", "get_page_links", "get_vehicle_provenance",
                "get_catalog_status", "compare_vehicles", "compare_trims", "compare_model_years",
                "get_variant_comparisons", "list_promotions", "finance_estimate");
        var callbacks = toolAuthorizationPolicy.readOnlyTools(new AiExecutionContext(TENANT_ID, false),
                        catalogNames).stream()
                .collect(java.util.stream.Collectors.toMap(callback -> callback.getToolDefinition().name(),
                        callback -> callback));
        assertThat(callbacks.keySet()).containsExactlyInAnyOrderElementsOf(catalogNames);

        var context = new org.springframework.ai.chat.model.ToolContext(Map.of(
                "tenantId", TENANT_ID.toString(), "conversationId", UUID.randomUUID().toString(), "locale", "es-MX"));
        var docId = JdbcClient.create(catalogDataSource)
                .sql("select document_id::text from ford_read.catalog_documents_v1 limit 1")
                .query(String.class).single();
        var queryArguments = Map.<String, Object>of("query", "pickup familiar Ranger",
                "model", "RANGER", "modelYear", 2027, "limit", 5);
        var observedVehicles = invokeCatalogCallback(callbacks, "search_vehicles", queryArguments, context);
        var calls = new java.util.LinkedHashMap<String, Map<String, Object>>();
        calls.put("get_vehicle", Map.of("model", "Ranger", "year", 2027, "trim", "XLT Gasolina 4x2"));
        calls.put("search_offers", Map.of("query", "promoción Ranger", "model", "RANGER", "modelYear", 2027,
                "offerType", "promotion", "asOf", LocalDate.now(ZoneOffset.UTC).toString(), "limit", 5));
        calls.put("search_vehicle_and_offers", Map.of("query", "Ranger familiar", "model", "RANGER", "year", 2027));
        calls.put("get_page_links", Map.of("sourceUrl", knownPageUrl, "offset", 0, "limit", 10,
                "includeAssets", false));
        calls.put("get_vehicle_provenance", Map.of("documentId", docId));
        calls.put("get_catalog_status", Map.of());
        calls.put("compare_vehicles", Map.of("vehicles", List.of(
                Map.of("model", "Ranger", "year", 2027), Map.of("model", "Ranger", "year", 2027))));
        calls.put("compare_trims", Map.of("model", "Ranger", "year", 2027,
                "trims", List.of("XL Gasolina 4x2", "XLT Gasolina 4x2"), "detail", "summary"));
        calls.put("compare_model_years", Map.of("model", "Ranger", "years", List.of(2026, 2027)));
        calls.put("get_variant_comparisons", Map.of("model", "Ranger", "year", 2027,
                "trim", "XLT Gasolina 4x2", "offset", 0, "limit", 3));
        calls.put("list_promotions", Map.of("model", "Ranger", "modelYear", 2027,
                "asOf", LocalDate.now(ZoneOffset.UTC).toString(), "activeOnly", false,
                "limit", 5, "detail", "summary", "query", "Ranger 2027"));
        calls.put("finance_estimate", Map.of("productCode", "SYNTHETIC_FIXED_60", "vehiclePrice", "907000.00",
                "downPayment", "100000.00", "termMonths", 60));

        for (var entry : calls.entrySet()) {
            var result = invokeCatalogCallback(callbacks, entry.getKey(), entry.getValue(), context);
            assertThat(objectMapper.readTree(result)).as("JSON result from %s", entry.getKey()).isNotNull();
        }
        assertThat(objectMapper.readTree(observedVehicles).path("semanticSearch").asText())
                .isEqualTo("ready");
    }

    private String invokeCatalogCallback(
            java.util.Map<String, org.springframework.ai.tool.ToolCallback> callbacks,
            String toolName, Object arguments, org.springframework.ai.chat.model.ToolContext context) throws Exception {
        var input = objectMapper.writeValueAsString(arguments);
        var result = callbacks.get(toolName).call(input, context);
        assertThat(result).as("non-empty result from %s", toolName).isNotBlank();
        return result;
    }

    @Test
    @Order(1)
    void acceptsAndCommitsEachQwenTurnBeforeSendingTheNextWithoutDeliveryBeans() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        Assumptions.assumeFalse(isFreshFullLiveMode(), "fresh acceptance owns the empty test database");
        Assumptions.assumeFalse(seedReplayEnabled(), "seeded-prefix mode runs only the natural-buyer replay");
        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
        assertScenarioFixturesAreAvailable();
        insertSyntheticBinding();
        extendReplayPrompt();
        var telemetryRunId = RUN_ID + "-smoke";
        var telemetry = new LocalQwenTurnTelemetry(Path.of("build/reports"), telemetryRunId);

        var firstStarted = System.nanoTime();
        var firstMeasurementMark = providerCallMeasurements.mark();
        postFixture("/fixtures/meta/qwen-two-turn-1.json", FIRST_MESSAGE_ID);
        var firstConversation = awaitCommittedReply(FIRST_MESSAGE_ID);
        appendTelemetry(telemetry, telemetryRunId, "qwen-two-turn-smoke", 1, firstStarted,
                firstMeasurementMark, firstConversation, FIRST_MESSAGE_ID, LocalQwenTurnTelemetry.Outcome.COMMITTED);

        var secondStarted = System.nanoTime();
        var secondMeasurementMark = providerCallMeasurements.mark();
        postFixture("/fixtures/meta/qwen-two-turn-2.json", SECOND_MESSAGE_ID);
        var secondConversation = awaitCommittedReply(SECOND_MESSAGE_ID);
        appendTelemetry(telemetry, telemetryRunId, "qwen-two-turn-smoke", 2, secondStarted,
                secondMeasurementMark, secondConversation, SECOND_MESSAGE_ID, LocalQwenTurnTelemetry.Outcome.COMMITTED);

        assertThat(firstConversation).isEqualTo(secondConversation);
        assertThat(countOutbound(firstConversation)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from channel_delivery where tenant_id=:tenant")
                .param("tenant", TENANT_ID).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @Order(2)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_SCENARIOS_ENABLED", matches = "true")
    void runsReviewedCatalogAndMemoryScenarios() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        Assumptions.assumeFalse(isFreshFullLiveMode(), "fresh acceptance runs in isolation");
        Assumptions.assumeFalse(seedReplayEnabled(), "seeded mode runs only its prefix diagnostic");
        runReviewedScenarios();
    }

    @Test
    @Order(3)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_10_REPLAY_ENABLED", matches = "true")
    void commitsTenNaturalSpanishTurnsAsASequentialCheckpoint() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        Assumptions.assumeFalse(isFreshFullLiveMode(), "fresh acceptance runs in isolation");
        Assumptions.assumeFalse(seedReplayEnabled(), "seeded mode runs only its prefix diagnostic");
        runNaturalBuyerReplay(10, "10");
    }

    @Test
    @Order(4)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_COMPARISON_PREFETCH_ENABLED", matches = "true")
    void suppliesExactPrefetchedComparisonToQwenAndPassesSafetyGate() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no Qwen call sent");
        Assumptions.assumeFalse(seedReplayEnabled(), "seeded-prefix mode runs only the natural-buyer replay");
        Assumptions.assumeFalse(isFreshFullLiveMode(), "fresh acceptance runs in isolation");
        var message = "De las dos versiones, Ranger 2027 XL Gasolina 4x2 y Ranger 2027 XLT Gasolina 4x2, "
                + "¿cuál tiene el precio publicado menor?";
        var answer = assistantService.respond(new AiExecutionContext(TENANT_ID, false, UUID.randomUUID()), message);
        var toolEvents = answer.decisionTrace().stream()
                .filter(event -> "tool".equals(event.stage()) && event.toolName() != null)
                .map(event -> new LocalQwenTranscriptWriter.ToolTrace(event.toolName(), event.outcome(),
                        event.evidenceCount(), event.reasonCode() != null && event.reasonCode().startsWith("replay_prefetch_")
                        ? LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH
                        : LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL))
                .toList();
        var names = catalogPort.nameIndexEntries();

        assertThat(toolEvents).anySatisfy(event -> {
            assertThat(event.origin()).isEqualTo(LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH);
            assertThat(event.toolName()).isEqualTo("compare_trims");
            assertThat(event.outcome()).isEqualTo("returned");
            assertThat(event.evidenceCount()).isEqualTo(1);
        });
        assertThat(answer.text()).isNotBlank();
        assertThat(LocalReplaySafetyAssertions.evaluate(new NaturalBuyerConversationScenario.Turn(25, message,
                        NaturalBuyerConversationScenario.Expectation.COMPARISON), answer.text(), toolEvents,
                List.of(), names, List.of(), true)).isEmpty();
    }

    @Test
    @Order(5)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_100_REPLAY_ENABLED", matches = "true")
    void runsRawQwenReplayWithoutPrefetch() throws Exception {
        Assumptions.assumeFalse(comparisonPrefetchEnabled(), "raw replay runs only with prefetch disabled");
        Assumptions.assumeFalse(isFreshFullLiveMode(), "fresh acceptance uses its dedicated prefetched test");
        Assumptions.assumeFalse(seedReplayEnabled(), "seeded mode runs only its prefix diagnostic");
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        runNaturalBuyerReplay(100, "100");
    }

    @Test
    @Order(6)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_100_REPLAY_ENABLED", matches = "true")
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_COMPARISON_PREFETCH_ENABLED", matches = "true")
    void runsPrefetchedQwenReplayAsSeparateArtifact() throws Exception {
        var mode = LocalQwenReplayExecutionMode.resolve(System.getenv());
        Assumptions.assumeFalse(mode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE
                        || mode == LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC,
                "seeded diagnostic and fresh acceptance use separate test lanes");
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        runNaturalBuyerReplay(100, "100-prefetch");
    }

    @Test
    @Order(7)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_REPLAY_MODE", matches = "SEEDED_DIAGNOSTIC")
    void runsOnlyValidatedSeedPrefixForPlumbingDiagnostics() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        var prefix = configuredSeedPrefixTurns();
        assertThat(prefix).isPositive();
        runNaturalBuyerReplay(prefix, "seeded-diagnostic");
    }

    @Test
    @Order(8)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_REPLAY_MODE", matches = "CHECKPOINT_LIVE_WINDOW")
    void runsCheckpointedLiveQwenWindow() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        var executionMode = LocalQwenReplayExecutionMode.resolve(System.getenv());
        assertThat(executionMode).isEqualTo(LocalQwenReplayExecutionMode.CHECKPOINT_LIVE_WINDOW);
        assertThat(seedReplayEnabled()).as("checkpoint mode must call Qwen live").isFalse();
        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();

        var turns = NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper());
        var checkpointPath = Path.of(System.getenv("FORD_QWEN_REPLAY_CHECKPOINT"));
        var checkpoint = LocalQwenReplayCheckpoint.read(checkpointPath, objectMapper);
        var startTurn = Integer.parseInt(System.getenv("FORD_QWEN_TARGET_START_TURN"));
        var endTurn = Integer.parseInt(System.getenv("FORD_QWEN_TARGET_END_TURN"));
        assertThat(startTurn).as("live window must begin immediately after checkpoint")
                .isEqualTo(checkpoint.checkpointTurn() + 1);
        assertThat(endTurn).isLessThanOrEqualTo(turns.size());
        checkpoint.validate(turns, SNAPSHOT_LABEL,
                java.util.Objects.requireNonNull(SNAPSHOT_SUMMARY).sha256(), checkpoint.checkpointTurn(),
                memoryProperties.workingMemoryTtl());
        assertThat(checkpoint.providerAccountId()).isEqualTo(ACCOUNT_ID);

        insertSyntheticBinding();
        extendReplayPrompt();
        var conversationId = new LocalQwenReplayCheckpointLoader(jdbc, objectMapper)
                .restore(checkpoint, TENANT_ID, "WHATSAPP", turns, SNAPSHOT_LABEL,
                        SNAPSHOT_SUMMARY.sha256(), checkpoint.checkpointTurn(), memoryProperties.workingMemoryTtl());
        var restoredKey = new com.ford.service.conversations.domain.ConversationMemoryKey(TENANT_ID,
                com.ford.service.channels.domain.Channel.WHATSAPP, checkpoint.providerAccountId(),
                checkpoint.participantId(), conversationId);
        var restoredMemory = new com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository(
                jdbc, objectMapper, memoryProperties).load(new com.ford.service.conversations.domain.MemoryQuery(
                restoredKey, "checkpoint verification", null, 20, 0));
        assertThat(restoredMemory.summary()).isEqualTo(checkpoint.memorySummary());
        assertThat(restoredMemory.durableFacts()).hasSize(checkpoint.durableFacts().size());
        assertThat(restoredMemory.recentMessages()).hasSize(checkpoint.messages().size());
        var window = turns.subList(startTurn - 1, endTurn);
        var prefix = "synthetic-qwen-checkpoint-" + RUN_ID;
        var telemetryRunId = RUN_ID + "-" + SNAPSHOT_LABEL.wireName() + "-checkpoint";
        var transcript = new LocalQwenTranscriptWriter(Path.of("build/reports"), RUN_ID, SNAPSHOT_SUMMARY,
                SNAPSHOT_LABEL, window.size(), replayMode(), executionMode, startTurn);
        var checkpointMetadata = new LocalQwenTranscriptWriter.CheckpointMetadata(
                checkpoint.checkpointId(), checkpoint.scenarioSha256(), checkpoint.checkpointTurn());
        transcript.recordCheckpointMetadata(checkpointMetadata);
        var telemetry = new LocalQwenTurnTelemetry(Path.of("build/reports"), telemetryRunId);
        for (var turn : window) {
            var providerMessageId = "wamid." + prefix + "-" + String.format(Locale.ROOT, "%03d", turn.number());
            var measurementMark = providerCallMeasurements.mark();
            var started = System.nanoTime();
            try {
                postSyntheticMessage(providerMessageId, checkpoint.participantId(), turn.message());
                var actualConversation = awaitCommittedReply(providerMessageId);
                assertThat(actualConversation).isEqualTo(conversationId);
                var answer = latestOutbound(actualConversation);
                assertThat(answer).isNotBlank();
                LocalReplayNoHandoffAssertions.assertNoHandoff(jdbc.sql(
                                "select count(*) from handoff_ticket where conversation_id=:conversation")
                        .param("conversation", actualConversation).query(Integer.class).single());
                var toolEvents = traceWithCurrentReplayPrefetch(toolTraceFor(providerMessageId), actualConversation);
                assertThat(toolEvents).as("target turn must use real catalog evidence: %s", turn.number())
                        .anySatisfy(event -> assertThat(event.origin())
                                .isNotEqualTo(LocalQwenTranscriptWriter.ToolTraceOrigin.RECORDED_SEED));
                assertThat(toolEvents).as("target turn must return catalog evidence: %s", turn.number())
                        .anySatisfy(event -> {
                            assertThat(event.outcome()).isEqualTo("returned");
                            assertThat(event.evidenceCount()).isNotNull().isPositive();
                        });
                assertThat(providerCallMeasurements.since(measurementMark))
                        .as("target turn must call live Qwen: %s", turn.number()).isNotEmpty();
                transcript.append(turn, answer, toolEvents, LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN);
                var nameIndex = applicationContext.getBeanProvider(CatalogNameIndexService.class).getIfAvailable();
                var currentNames = nameIndex == null ? List.<com.ford.service.catalog.api.VehicleNameIndexEntry>of()
                        : nameIndex.currentEntries();
                var violations = LocalReplaySafetyAssertions.evaluate(turn, answer,
                        LocalReplayTurnEvidence.forTurn(providerMessageId, Map.of(providerMessageId, toolEvents)),
                        replyOptionsFor(actualConversation), currentNames,
                        approvedInventoryTexts(actualConversation), true, false);
                assertThat(violations).as("checkpoint live-window safety turn=%s", turn.number()).isEmpty();
                appendTelemetry(telemetry, telemetryRunId, "natural-buyer-checkpoint-window", turn.number(),
                        started, measurementMark, actualConversation, providerMessageId,
                        LocalQwenTurnTelemetry.Outcome.COMMITTED, checkpointMetadata);
            } catch (Exception | AssertionError failure) {
                transcript.recordFailure(turn, safeFailureCategory(failure), toolTraceFor(providerMessageId));
                appendTelemetry(telemetry, telemetryRunId, "natural-buyer-checkpoint-window", turn.number(),
                        started, measurementMark, conversationId, providerMessageId,
                        LocalQwenTurnTelemetry.Outcome.FAILED, checkpointMetadata);
                throw new AssertionError("checkpoint live window failed at turn " + turn.number()
                        + "; transcript=" + transcript.path() + "; reason=" + failure.getMessage(), failure);
            }
        }
        var checkpointHandoffCount = jdbc.sql("select count(*) from handoff_ticket where conversation_id=:conversation")
                .param("conversation", conversationId).query(Integer.class).single();
        LocalReplayNoHandoffAssertions.assertNoHandoff(checkpointHandoffCount);
        transcript.recordHandoffCount(checkpointHandoffCount);
        transcript.complete();
        assertThat(countOutbound(conversationId))
                .isEqualTo(checkpoint.messages().size() / 2 + window.size());
        assertThat(jdbc.sql("select count(distinct provider_message_id) from conversation_turn "
                        + "where tenant_id=:tenant and conversation_id=:conversation and provider_message_id like :prefix")
                .param("tenant", TENANT_ID).param("conversation", conversationId)
                .param("prefix", "wamid." + prefix + "%")
                .query(Integer.class).single()).isEqualTo(window.size());
        System.out.println("Checkpoint live-window transcript written: " + transcript.path()
                + "; telemetry: " + telemetry.path());
    }

    @Test
    @Order(9)
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_REPLAY_MODE", matches = "FRESH_FULL_LIVE")
    @EnabledIfEnvironmentVariable(named = "FORD_QWEN_100_REPLAY_ENABLED", matches = "true")
    void acceptsFreshOneHundredTurnAllLiveRunOnly() throws Exception {
        Assumptions.assumeTrue(PREFLIGHT_PASSED, "snapshot/BGE preflight did not pass; no webhook sent");
        Assumptions.assumeTrue(comparisonPrefetchEnabled(), "final acceptance uses the approved prefetched lane");
        runNaturalBuyerReplay(100, "100-fresh-live");
    }

    private void runNaturalBuyerReplay(int expectedTurns, String runLabel) throws Exception {
        var executionMode = LocalQwenReplayExecutionMode.resolve(System.getenv());
        var checkpointCaptureTurn = configuredCheckpointCaptureTurn();
        if (executionMode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE) {
            assertThat(expectedTurns).as("fresh all-live acceptance has fixed scenario length").isEqualTo(100);
            assertThat(seedReplayEnabled()).as("fresh all-live acceptance has no seed adapter").isFalse();
            assertThat(jdbc.sql("select count(*) from conversation where tenant_id=:tenant")
                    .param("tenant", TENANT_ID).query(Integer.class).single())
                    .as("fresh all-live acceptance starts with empty tenant state").isZero();
        }
        if (executionMode == LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC) {
            assertThat(comparisonPrefetchEnabled())
                    .as("seeded mode adapter is installed only in prefetched harness").isTrue();
        }
        if (executionMode == LocalQwenReplayExecutionMode.CHECKPOINT_LIVE_WINDOW) {
            throw new IllegalStateException("checkpoint mode must use the checkpoint live-window runner");
        }
        if (checkpointCaptureTurn != null) {
            assertThat(checkpointCaptureTurn).as("checkpoint capture must be inside replay prefix")
                    .isLessThanOrEqualTo(expectedTurns);
        }
        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DeterministicCatalogAnswerService.class)).isNotEmpty();
        var prefetchEnabled = comparisonPrefetchEnabled();
        assertThat(applicationContext.getBean(ChatCompletionPort.class)
                instanceof ReplayPrefetchingChatCompletionPort).isEqualTo(prefetchEnabled);
        assertThat(applicationContext.getBean(com.ford.service.ai.application.AssistantService.class)
                instanceof LocalReplayQwenAssistantService).isEqualTo(prefetchEnabled);
        assertScenarioFixturesAreAvailable();
        insertSyntheticBinding();
        extendReplayPrompt();
        var turns = NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper());
        assertThat(turns).hasSize(100);
        var replayTurns = turns.subList(0, expectedTurns);
        var prefix = "synthetic-qwen-" + runLabel + "-" + RUN_ID;
        var sender = "synthetic-qwen-" + runLabel + "-sender-" + RUN_ID;
        var replayMode = replayMode();
        var telemetryRunId = RUN_ID + "-" + SNAPSHOT_LABEL.wireName() + "-" + runLabel;
        var transcript = new LocalQwenTranscriptWriter(Path.of("build/reports"), RUN_ID,
                java.util.Objects.requireNonNull(SNAPSHOT_SUMMARY, "snapshot preflight metadata is required"),
                java.util.Objects.requireNonNull(SNAPSHOT_LABEL, "snapshot label is required"),
                expectedTurns, replayMode, executionMode);
        transcript.recordScenarioSha256(LocalQwenReplayCheckpoint.scenarioSha256(turns));
        var telemetry = new LocalQwenTurnTelemetry(Path.of("build/reports"), telemetryRunId);
        var seededReplyCount = executionMode == LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC
                ? configuredSeedReplyCount() : 0;
        var eventsByProviderMessageId = new java.util.HashMap<String, List<LocalQwenTranscriptWriter.ToolTrace>>();
        UUID conversationId = null;
        var committed = 0;
        for (var turn : replayTurns) {
            var turnIndex = turn.number();
            var providerMessageId = "wamid." + prefix + "-" + String.format(Locale.ROOT, "%03d", turnIndex);
            var turnStarted = System.nanoTime();
            var measurementMark = providerCallMeasurements.mark();
            try {
                postSyntheticMessage(providerMessageId, sender, turn.message());
                var committedConversation = awaitCommittedReply(providerMessageId);
                if (conversationId == null) conversationId = committedConversation;
                assertThat(committedConversation)
                        .as("conversation continuity at natural buyer turn %s", turnIndex)
                        .isEqualTo(conversationId);
                var answer = latestOutbound(committedConversation);
                assertThat(answer).as("committed reply at turn %s", turnIndex).isNotBlank();
                LocalReplayNoHandoffAssertions.assertNoHandoff(jdbc.sql(
                                "select count(*) from handoff_ticket where conversation_id=:conversation")
                        .param("conversation", committedConversation).query(Integer.class).single());
                var replyOrigin = isSeededReply(turnIndex)
                        ? LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED
                        : LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN;
                var toolEvents = traceWithCurrentReplayPrefetch(toolTraceFor(providerMessageId), committedConversation);
                toolEvents = withRecordedSeedTraces(toolEvents, turn);
                eventsByProviderMessageId.put(providerMessageId, toolEvents);
                transcript.append(turn, answer, toolEvents, replyOrigin);
                if (replayMode == LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN
                        && replyOrigin == LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN) {
                    var precision = new LocalReplayPrecisionEvaluator();
                    var catalogPrecision = turn.expectation() == NaturalBuyerConversationScenario.Expectation.CATALOG_FACT
                            || turn.expectation() == NaturalBuyerConversationScenario.Expectation.OFFER
                            ? precision.evaluateCatalog(turn, answer,
                            replayPrecisionEvidence.currentTurnEvidence(committedConversation)) : null;
                    var memoryPrecision = turn.expectation() == NaturalBuyerConversationScenario.Expectation.MEMORY_RECALL
                            ? precision.evaluateMemory(turn, answer, replayTurns.stream()
                            .filter(prior -> prior.number() < turnIndex)
                            .map(NaturalBuyerConversationScenario.Turn::message).toList()) : null;
                    transcript.recordPrecision(turn, catalogPrecision, memoryPrecision);
                }
                var nameIndex = applicationContext.getBeanProvider(CatalogNameIndexService.class).getIfAvailable();
                var currentNames = nameIndex == null ? List.<com.ford.service.catalog.api.VehicleNameIndexEntry>of()
                        : nameIndex.currentEntries();
                var currentTurnEvents = LocalReplayTurnEvidence.forTurn(providerMessageId,
                        eventsByProviderMessageId);
                var safetyViolations = LocalReplaySafetyAssertions.evaluate(turn, answer, currentTurnEvents,
                        replyOptionsFor(committedConversation), currentNames,
                        approvedInventoryTexts(committedConversation), replayMode ==
                                LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN,
                        replyOrigin == LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED);
                assertThat(safetyViolations)
                        .as("per-turn safety checks: turn=%s expectation=%s", turnIndex, turn.expectation())
                        .isEmpty();
                if (executionMode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE) {
                    assertThat(providerCallMeasurements.since(measurementMark))
                            .as("fresh acceptance requires a live Qwen call for turn %s", turnIndex).isNotEmpty();
                }
                appendTelemetry(telemetry, telemetryRunId, "natural-buyer-100", turnIndex, turnStarted,
                        measurementMark, committedConversation, providerMessageId,
                        LocalQwenTurnTelemetry.Outcome.COMMITTED);
                if (checkpointCaptureTurn != null && checkpointCaptureTurn == turnIndex) {
                    var checkpointId = RUN_ID + "-turn-" + String.format(Locale.ROOT, "%03d", turnIndex);
                    var artifactOrigin = executionMode == LocalQwenReplayExecutionMode.SEEDED_DIAGNOSTIC
                            ? "SEEDED_REPLAY_EXPORT" : "LIVE_REPLAY_EXPORT";
                    var checkpointPath = new LocalQwenReplayCheckpointExporter(jdbc, objectMapper).export(
                            Path.of("build/reports"), checkpointId, TENANT_ID, committedConversation,
                            turnIndex, turns, SNAPSHOT_LABEL, SNAPSHOT_SUMMARY.sha256(), artifactOrigin);
                    System.out.println("Synthetic replay checkpoint exported: " + checkpointPath
                            + " origin=" + artifactOrigin);
                }
                committed++;
                if (turnIndex == 5 && !isSeededReply(turnIndex)) {
                    var route = jdbc.sql("""
                            select route from conversation_decision_event
                             where tenant_id=:tenant and conversation_id=:conversation and stage='route'
                             order by event_sequence desc limit 1
                            """)
                            .param("tenant", TENANT_ID).param("conversation", committedConversation)
                            .query(String.class).optional().orElse("no_route_event");
                    assertThat(jdbc.sql("""
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
                            .param("tenant", TENANT_ID).param("messageId", providerMessageId)
                            .query(Integer.class).single())
                             .as("turn 5 recommendation must use successful semantic catalog evidence")
                             .isGreaterThan(0);
                }
            } catch (AssertionError failure) {
                var detail = failure.getMessage() == null ? "" : failure.getMessage();
                var category = detail.contains("SAFE_FALLBACK_REVIEW")
                        ? LocalQwenTranscriptWriter.FailureCategory.SAFE_FALLBACK_REVIEW
                        : detail.contains("MISSING_CATALOG_EVIDENCE")
                        ? LocalQwenTranscriptWriter.FailureCategory.MISSING_CATALOG_EVIDENCE
                        : detail.contains("timed out")
                        ? LocalQwenTranscriptWriter.FailureCategory.TURN_TIMEOUT
                        : LocalQwenTranscriptWriter.FailureCategory.INVALID_STATE;
                var toolEvents = conversationId == null ? toolTraceFor(providerMessageId)
                        : traceWithCurrentReplayPrefetch(toolTraceFor(providerMessageId), conversationId);
                toolEvents = withRecordedSeedTraces(toolEvents, turn);
                var comparisonStatus = conversationId == null || replayPrecisionEvidence == null
                        ? null : replayPrecisionEvidence.currentTurnComparisonStatus(conversationId);
                transcript.recordFailure(turn, category, toolEvents, comparisonStatus);
                appendTelemetry(telemetry, telemetryRunId, "natural-buyer-100", turnIndex, turnStarted,
                        measurementMark, conversationId, providerMessageId, LocalQwenTurnTelemetry.Outcome.FAILED);
                throw new AssertionError("natural buyer replay stopped at turn " + turnIndex
                        + " of " + expectedTurns + "; committed=" + committed
                        + "; transcript=" + transcript.path()
                        + "; reason=" + failure.getMessage(), failure);
            }
        }

        assertThat(committed).isEqualTo(expectedTurns);
        assertThat(countOutbound(conversationId)).isEqualTo(expectedTurns);
        assertThat(jdbc.sql("select count(distinct provider_message_id) from conversation_turn "
                        + "where tenant_id=:tenant and conversation_id=:conversation and provider_message_id like :prefix")
                .param("tenant", TENANT_ID).param("conversation", conversationId)
                .param("prefix", "wamid." + prefix + "%")
                .query(Integer.class).single())
                .isEqualTo(expectedTurns);
        var handoffCount = jdbc.sql("select count(*) from handoff_ticket where conversation_id=:conversation")
                .param("conversation", conversationId).query(Integer.class).single();
        LocalReplayNoHandoffAssertions.assertNoHandoff(handoffCount);
        transcript.recordHandoffCount(handoffCount);
        if (executionMode == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE) {
            transcript.completeFreshFullLive();
        } else {
            transcript.complete(seededReplyCount);
        }
        System.out.println("Local Qwen transcript written: " + transcript.path()
                + " and " + transcript.htmlPath() + "; turn telemetry: " + telemetry.path());
    }

    private void runReviewedScenarios() throws Exception {
        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OutboxDeliveryRouter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChannelDeliveryPort.class)).isEmpty();
        insertSyntheticBinding();
        extendReplayPrompt();
        var cases = CatalogMemoryEvaluationScenario.load(new tools.jackson.databind.ObjectMapper());
        var snapshot = java.util.Objects.requireNonNull(SNAPSHOT_SUMMARY, "snapshot preflight metadata is required");
        var label = java.util.Objects.requireNonNull(SNAPSHOT_LABEL, "snapshot label is required");
        var telemetryRunId = RUN_ID + "-scenarios-" + label.wireName();
        var telemetry = new LocalQwenTurnTelemetry(Path.of("build/reports"), telemetryRunId);
         var report = new LocalQwenEvaluationReportWriter(Path.of("build/reports"), telemetryRunId,
                 label, snapshot.sha256(), replayModelConfigId(),
                 applicationContext.getBeanProvider(com.ford.service.ai.application.CatalogNameIndexService.class)
                         .getIfAvailable() != null);
        var results = new java.util.ArrayList<CatalogMemoryEvaluationReport.CaseResult>();

        for (var testCase : cases) {
            var transcriptRunId = RUN_ID + "-" + testCase.id();
            var transcript = new LocalQwenTranscriptWriter(Path.of("build/reports"), transcriptRunId,
                    snapshot, label, testCase.turns().size(), replayMode());
            var conversationIds = new java.util.LinkedHashMap<String, UUID>();
            var finalAnswers = new java.util.LinkedHashMap<String, String>();
            var evidenceKinds = java.util.EnumSet.noneOf(CatalogMemoryEvaluationScenario.EvidenceKind.class);
            var memoryAssertionsPass = true;
            var workflowFailed = false;

            for (var index = 0; index < testCase.turns().size() && !workflowFailed; index++) {
                var scenarioTurn = testCase.turns().get(index);
                var turnNumber = index + 1;
                var providerMessageId = "wamid.synthetic-qwen-" + RUN_ID + "-" + testCase.id()
                        + "-" + String.format(Locale.ROOT, "%03d", turnNumber);
                var sender = "synthetic-qwen-eval-" + RUN_ID + "-" + testCase.id()
                        + "-" + scenarioTurn.conversationKey();
                var startedNanos = System.nanoTime();
                var measurementMark = providerCallMeasurements.mark();
                UUID conversation = null;
                var transcriptTurn = new NaturalBuyerConversationScenario.Turn(turnNumber, scenarioTurn.message(),
                        scenarioTurn.expectation());
                try {
                    postSyntheticMessage(providerMessageId, sender, scenarioTurn.message());
                    conversation = awaitCommittedReply(providerMessageId);
                    var priorConversation = conversationIds.putIfAbsent(scenarioTurn.conversationKey(), conversation);
                    if (priorConversation != null) {
                        assertThat(conversation).as("conversation continuity for %s", scenarioTurn.conversationKey())
                                .isEqualTo(priorConversation);
                    } else {
                        assertThat(conversationIds.entrySet().stream()
                                .filter(entry -> !entry.getKey().equals(scenarioTurn.conversationKey()))
                                .map(Map.Entry::getValue)).doesNotContain(conversation);
                    }
                    var answer = latestOutbound(conversation);
                    assertThat(answer).as("committed response for case %s turn %s", testCase.id(), turnNumber)
                            .isNotBlank();
                    finalAnswers.put(scenarioTurn.conversationKey(), answer);
                    var toolEvents = toolTraceFor(providerMessageId);
                    evidenceKinds.clear();
                    evidenceKinds.addAll(CatalogMemoryEvaluationReport.evidenceKinds(toolEvents));
                    transcript.append(transcriptTurn, answer, toolEvents);
                    var shape = memoryShape(conversation);
                    if (testCase.category() == CatalogMemoryEvaluationScenario.Category.LONG_HISTORY) {
                        memoryAssertionsPass &= shape.durableFactCount() <= memoryProperties.factLimit()
                                && shape.summaryChars() <= memoryProperties.summaryMaxChars();
                    }
                    appendTelemetry(telemetry, telemetryRunId, testCase.id(), turnNumber, startedNanos,
                            measurementMark, conversation, providerMessageId,
                            LocalQwenTurnTelemetry.Outcome.COMMITTED);
                } catch (Exception | AssertionError failure) {
                    var safeCategory = safeFailureCategory(failure);
                    var toolEvents = toolTraceFor(providerMessageId);
                    transcript.recordFailure(transcriptTurn, safeCategory, toolEvents);
                    appendTelemetry(telemetry, telemetryRunId, testCase.id(), turnNumber, startedNanos,
                            measurementMark, conversation, providerMessageId,
                            LocalQwenTurnTelemetry.Outcome.FAILED);
                    var failureResult = new CatalogMemoryEvaluationReport.CaseResult(testCase.id(),
                            testCase.category(), testCase.baselineStatus(), testCase.candidateIntent(),
                            testCase.criticality(), false,
                            testCase.criticality() == CatalogMemoryEvaluationScenario.Criticality.CRITICAL,
                            List.of("WORKFLOW_FAILED", safeCategory.name()));
                    report.append(failureResult);
                    results.add(failureResult);
                    workflowFailed = true;
                }
            }

            if (workflowFailed) continue;
            transcript.complete();
            var targetConversationKey = testCase.turns().getLast().conversationKey();
            var result = CatalogMemoryEvaluationReport.evaluate(testCase,
                    finalAnswers.get(targetConversationKey), evidenceKinds, memoryAssertionsPass);
            report.append(result);
            results.add(result);
        }
        report.complete();
        assertThat(results).hasSize(cases.size());
        System.out.println("Reviewed local Qwen cases: " + report.reportPath()
                + "; per-turn telemetry: " + telemetry.path());
    }

    private String replayModelConfigId() {
        return System.getenv().getOrDefault("FORD_QWEN_REPLAY_MODEL", "qwen3.5:4b") + "_ctx8192_predict160";
    }

    private static boolean comparisonPrefetchEnabled() {
        return "true".equalsIgnoreCase(System.getenv("FORD_QWEN_COMPARISON_PREFETCH_ENABLED"));
    }

    private static boolean isFreshFullLiveMode() {
        return LocalQwenReplayExecutionMode.resolve(System.getenv())
                == LocalQwenReplayExecutionMode.FRESH_FULL_LIVE;
    }

    private static LocalQwenTranscriptWriter.ReplayMode replayMode() {
        return comparisonPrefetchEnabled() ? LocalQwenTranscriptWriter.ReplayMode.PREFETCHED_QWEN
                : LocalQwenTranscriptWriter.ReplayMode.RAW_QWEN;
    }

    private static LocalQwenTranscriptWriter.FailureCategory safeFailureCategory(Throwable failure) {
        var message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("SAFE_FALLBACK_REVIEW")) {
            return LocalQwenTranscriptWriter.FailureCategory.SAFE_FALLBACK_REVIEW;
        }
        if (failure instanceof java.net.http.HttpTimeoutException
                || message.toLowerCase(Locale.ROOT).contains("timed out")) {
            return LocalQwenTranscriptWriter.FailureCategory.TURN_TIMEOUT;
        }
        return LocalQwenTranscriptWriter.FailureCategory.INVALID_STATE;
    }

    private void appendTelemetry(LocalQwenTurnTelemetry telemetry, String telemetryRunId, String scenarioId,
                                 int turnNumber, long startedNanos, int measurementMark,
                                 UUID conversationId, String providerMessageId,
                                 LocalQwenTurnTelemetry.Outcome outcome) throws Exception {
        appendTelemetry(telemetry, telemetryRunId, scenarioId, turnNumber, startedNanos, measurementMark,
                conversationId, providerMessageId, outcome, null);
    }

    private void appendTelemetry(LocalQwenTurnTelemetry telemetry, String telemetryRunId, String scenarioId,
                                 int turnNumber, long startedNanos, int measurementMark,
                                 UUID conversationId, String providerMessageId,
                                 LocalQwenTurnTelemetry.Outcome outcome,
                                 LocalQwenTranscriptWriter.CheckpointMetadata checkpointMetadata) throws Exception {
        var measurements = providerCallMeasurements.since(measurementMark);
        var tools = providerMessageId == null ? List.<LocalQwenTranscriptWriter.ToolTrace>of()
                : toolTraceFor(providerMessageId);
        if (scenarioId.equals("natural-buyer-100") && turnNumber <= 100) {
            var turn = NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper())
                    .get(turnNumber - 1);
            tools = withRecordedSeedTraces(tools, turn);
        }
        var summary = java.util.Objects.requireNonNull(SNAPSHOT_SUMMARY,
                "snapshot metadata is required for turn telemetry");
        var label = java.util.Objects.requireNonNull(SNAPSHOT_LABEL,
                "snapshot label is required for turn telemetry");
        var replyOrigin = scenarioId.equals("natural-buyer-100") && isSeededReply(turnNumber)
                ? LocalQwenTranscriptWriter.ReplyOrigin.RECORDED_SEED
                : LocalQwenTranscriptWriter.ReplyOrigin.LIVE_QWEN;
        String scenarioHash = null;
        if (scenarioId.startsWith("natural-buyer")) {
            scenarioHash = LocalQwenReplayCheckpoint.scenarioSha256(
                    NaturalBuyerConversationScenario.load(new tools.jackson.databind.ObjectMapper()));
        }
        if (checkpointMetadata != null) {
            assertThat(scenarioHash).isEqualTo(checkpointMetadata.scenarioSha256());
        }
        var record = LocalQwenTurnTelemetry.TurnRecord.from(telemetryRunId, scenarioId, turnNumber, label,
                summary.sha256(), replayModelConfigId(),
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000),
                measurements, tools, memoryShape(conversationId), replyOrigin,
                LocalQwenReplayExecutionMode.resolve(System.getenv()),
                scenarioHash, checkpointMetadata == null ? null : checkpointMetadata.checkpointId(),
                checkpointMetadata == null ? null : checkpointMetadata.checkpointTurn(), outcome);
        telemetry.append(record);
    }

    private static int configuredSeedPrefixTurns() {
        var transcript = System.getenv("FORD_QWEN_REPLAY_SEED_TRANSCRIPT");
        if (transcript == null || transcript.isBlank()) return 0;
        var value = System.getenv("FORD_QWEN_REPLAY_SEED_THROUGH_TURN");
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("seed-through turn must be an integer", failure);
        }
    }

    private static Integer configuredCheckpointCaptureTurn() {
        var value = System.getenv("FORD_QWEN_REPLAY_CHECKPOINT_CAPTURE_TURN");
        if (value == null || value.isBlank()) return null;
        try {
            var turn = Integer.parseInt(value);
            if (turn < 1 || turn > 100) throw new NumberFormatException("outside range");
            return turn;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("checkpoint capture turn must be from 1 through 100", failure);
        }
    }

    private static boolean seedReplayEnabled() {
        var transcript = System.getenv("FORD_QWEN_REPLAY_SEED_TRANSCRIPT");
        return transcript != null && !transcript.isBlank();
    }

    private static java.util.Set<Integer> configuredForcedLiveTurns() {
        var value = System.getenv("FORD_QWEN_REPLAY_FORCE_LIVE_TURNS");
        if (value == null || value.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip).filter(turn -> !turn.isEmpty()).map(Integer::parseInt)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean isSeededReply(int turnNumber) {
        return turnNumber <= configuredSeedPrefixTurns() && !configuredForcedLiveTurns().contains(turnNumber);
    }

    private static int configuredSeedReplyCount() {
        var prefix = configuredSeedPrefixTurns();
        return (int) java.util.stream.IntStream.rangeClosed(1, prefix)
                .filter(QwenTwoTurnWebhookReplayIntegrationTest::isSeededReply).count();
    }

    private LocalQwenTurnTelemetry.MemoryShape memoryShape(UUID conversationId) {
        if (conversationId == null) return new LocalQwenTurnTelemetry.MemoryShape(0, 0, 0, 0, 0);
        return jdbc.sql("""
                select (select count(*) from conversation_message message
                         where message.conversation_id=conversation.id) as stored_messages,
                       coalesce(jsonb_array_length(memory.facts_json), 0) as durable_facts,
                       length(coalesce(memory.summary, '')) as summary_chars,
                       (select count(*) from conversation_memory_item item
                         where item.conversation_id=conversation.id and item.active) as active_memory_items
                  from conversation
                  left join conversation_memory memory on memory.conversation_id=conversation.id
                 where conversation.id=:conversation
                """)
                .param("conversation", conversationId)
                .query((rs, row) -> new LocalQwenTurnTelemetry.MemoryShape(
                        rs.getInt("stored_messages"), rs.getInt("durable_facts"),
                        rs.getInt("summary_chars"), rs.getInt("active_memory_items"), 0))
                .single();
    }

    private List<LocalQwenTranscriptWriter.ToolTrace> toolTraceFor(String providerMessageId) {
        return jdbc.sql("""
                select event.tool_name, event.reason_code, event.outcome, event.evidence_count
                  from conversation_turn turn
                  join conversation_decision_event event
                    on event.tenant_id=turn.tenant_id
                   and event.conversation_id=turn.conversation_id
                   and event.receipt_id=turn.receipt_id
                 where turn.tenant_id=:tenant
                   and turn.provider_message_id=:messageId
                   and event.stage='tool'
                 order by event.event_sequence
                """)
                .param("tenant", TENANT_ID).param("messageId", providerMessageId)
                .query((rs, row) -> new LocalQwenTranscriptWriter.ToolTrace(
                        rs.getString("tool_name"), rs.getString("outcome"),
                        (Integer) rs.getObject("evidence_count"),
                        rs.getString("reason_code") != null && rs.getString("reason_code").startsWith("replay_prefetch_")
                                ? LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH
                                : LocalQwenTranscriptWriter.ToolTraceOrigin.MODEL))
                .list();
    }

    private List<LocalQwenTranscriptWriter.ToolTrace> traceWithCurrentReplayPrefetch(
            List<LocalQwenTranscriptWriter.ToolTrace> persistedEvents, UUID conversationId) {
        var events = new java.util.ArrayList<>(persistedEvents);
        if (replayPrecisionEvidence != null) {
            for (var prefetch : replayPrecisionEvidence.currentTurnToolTraces(conversationId)) {
                var alreadyPersisted = events.stream().anyMatch(event ->
                        event.origin() == LocalQwenTranscriptWriter.ToolTraceOrigin.REPLAY_PREFETCH
                                && event.toolName().equals(prefetch.toolName())
                                && java.util.Objects.equals(event.evidenceCount(), prefetch.evidenceCount()));
                if (!alreadyPersisted) events.add(prefetch);
            }
        }
        return List.copyOf(events);
    }

    private List<LocalQwenTranscriptWriter.ToolTrace> withRecordedSeedTraces(
            List<LocalQwenTranscriptWriter.ToolTrace> existing,
            NaturalBuyerConversationScenario.Turn turn) {
        if (!isSeededReply(turn.number())) return existing;
        var replayPort = applicationContext.getBeanProvider(ReplayPrefetchingChatCompletionPort.class)
                .getIfAvailable();
        if (replayPort == null) return existing;
        var seedTraces = replayPort.seedToolTracesForTurn(turn.number(), turn.message());
        if (seedTraces.isEmpty()) return existing;
        var events = new java.util.ArrayList<>(existing);
        events.addAll(seedTraces);
        return List.copyOf(events);
    }

    private void extendReplayPrompt() {
        var current = systemPromptService.current();
        var replayPrompt = LocalQwenReplayPrompt.extend(current.content());
        if (!replayPrompt.equals(current.content())) {
            systemPromptService.replace(replayPrompt, current.version(), "local-qwen-replay");
        }
    }

    private void postFixture(String resource, String messageId)
            throws Exception {
        var template = new org.springframework.core.io.ClassPathResource(resource);
        var body = new String(template.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("{{run}}", RUN_ID);
        if (!body.contains(messageId) || !body.contains(SENDER)) {
            throw new IllegalStateException("synthetic Qwen fixture identifiers did not match the run manifest");
        }
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + port + "/webhooks/meta/whatsapp"))
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature(body))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    private void postSyntheticMessage(String messageId, String sender, String text) throws Exception {
        var body = """
                {"entry":[{"id":"synthetic-qwen-waba","changes":[{"value":{"metadata":{"phone_number_id":"%s"},"messages":[{"id":"%s","from":"%s","text":{"body":"%s"}}]}}]}]}
                """.formatted(ACCOUNT_ID, messageId, sender, text);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + port + "/webhooks/meta/whatsapp"))
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature(body))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("synthetic turn acceptance").isEqualTo(202);
    }

    private UUID awaitCommittedReply(String providerMessageId) throws InterruptedException {
        var deadline = System.nanoTime() + REPLAY_TURN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var turn = jdbc.sql("""
                    select turn.tenant_id, turn.conversation_id, turn.state, turn.failure_category,
                           review.reason_code as review_reason
                      from conversation_turn turn
                      left join conversation_review_item review
                        on review.tenant_id=turn.tenant_id
                       and review.conversation_id=turn.conversation_id
                       and review.receipt_id=turn.receipt_id
                     where turn.tenant_id=:tenant and turn.provider_message_id=:messageId
                    """)
                    .param("tenant", TENANT_ID).param("messageId", providerMessageId)
                    .query((rs, row) -> new TurnRow(rs.getObject("tenant_id", UUID.class),
                            rs.getObject("conversation_id", UUID.class), rs.getString("state"),
                            rs.getString("failure_category"), rs.getString("review_reason")))
                    .optional();
            if (turn.isPresent() && "REPLY_COMMITTED".equals(turn.get().state())
                    && countOutbound(turn.get().conversationId()) > 0) {
                return turn.get().conversationId();
            }
            if (turn.isPresent() && !SetOfAcceptedStates.contains(turn.get().state())) {
                throw new AssertionError("local replay stopped at invalid state for message "
                        + providerMessageId + ": " + turn.get().state()
                        + "; category=" + turn.get().failureCategory()
                        + "; reviewReason=" + turn.get().reviewReason());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("local replay reply timed out for message " + providerMessageId);
    }

    private int countOutbound(UUID conversationId) {
        return jdbc.sql("select count(*) from conversation_message where conversation_id=:conversation "
                        + "and direction='OUTBOUND' and coalesce(length(content),0)>0")
                .param("conversation", conversationId).query(Integer.class).single();
    }

    private String latestOutbound(UUID conversationId) {
        return jdbc.sql("select content from conversation_message where conversation_id=:conversation "
                        + "and direction='OUTBOUND' order by received_at desc, id desc limit 1")
                .param("conversation", conversationId).query(String.class).single();
    }

    private List<ReplyOption> replyOptionsFor(UUID conversationId) throws Exception {
        var payloads = jdbc.sql("select payload::text from outbox_event where tenant_id=:tenant and aggregate_id=:conversation")
                .param("tenant", TENANT_ID).param("conversation", conversationId)
                .query(String.class).list();
        var options = new java.util.ArrayList<ReplyOption>();
        for (var payload : payloads) {
            var replyOptions = objectMapper.readTree(payload).path("replyOptions");
            if (!replyOptions.isArray()) continue;
            for (var option : replyOptions) {
                options.add(new ReplyOption(option.path("id").asText(), option.path("title").asText()));
            }
        }
        return List.copyOf(options);
    }

    private List<String> approvedInventoryTexts(UUID conversationId) {
        return jdbc.sql("""
                select approved_text
                  from inventory_approval
                 where tenant_id=:tenant and conversation_id=:conversation and status='APPROVED'
                   and approved_text is not null
                   and verification_expires_at > current_timestamp
                """)
                .param("tenant", TENANT_ID).param("conversation", conversationId)
                .query(String.class).list();
    }

    private void assertScenarioFixturesAreAvailable() {
        assertThat(catalogPort.find(new VehicleLookup("Ranger", 2027, "XLT Gasolina 4x2")))
                .hasValueSatisfying(vehicle -> {
                    assertThat(vehicle.price()).isEqualByComparingTo("907000.00");
                    assertThat(vehicle.priceSourceUrl()).isNotBlank();
                    assertThat(vehicle.observedAt()).isNotNull();
                });
        assertThat(catalogPort.find(new VehicleLookup("Ranger", 2027, "XL Gasolina 4x2")))
                .hasValueSatisfying(vehicle -> assertThat(vehicle.price()).isEqualByComparingTo("775000.00"));
        assertThat(catalogPort.search(new VehicleSearchQuery(
                "Maverick", "Maverick", 2027, null, null, null, null, null, 5))).isEmpty();
        var asOf = LocalDate.now(ZoneOffset.UTC);
        assertThat(catalogPort.promotions(new PromotionQuery("Ranger", 2027, asOf, false, 20)).results())
                .filteredOn(promotion -> promotion.model().equalsIgnoreCase("RANGER"))
                .singleElement().satisfies(promotion -> {
                    assertThat(promotion.title()).isEqualTo("Ranger 2027");
                    assertThat(promotion.discountAmount()).isEqualByComparingTo("80000");
                    assertThat(promotion.sourceUrl()).contains("forduruapan.mx");
                    assertThat(promotion.observedAt()).isNotNull();
                });
        var terms = financeTermsRepository.findActive(TENANT_ID, "SYNTHETIC_FIXED_60", 60).orElseThrow();
        assertThat(terms.annualRate()).isEqualByComparingTo("0.11250");
        assertThat(terms.openingFee().amount()).isEqualByComparingTo("1500.00");
        assertThat(financeTermsRepository.findActive(UUID.randomUUID(), "SYNTHETIC_FIXED_60", 60)).isEmpty();
    }

    private void insertSyntheticBinding() {
        jdbc.sql("""
                insert into channel_binding
                  (id, webhook_key_hash, tenant_id, channel, provider_account_id, secret_reference,
                   enabled, created_at, updated_at)
                select :id, null, :tenant, 'WHATSAPP', :account, 'synthetic-qwen-secret', true,
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
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record TurnRow(UUID tenantId, UUID conversationId, String state,
                           String failureCategory, String reviewReason) { }

    private static final java.util.Set<String> SetOfAcceptedStates = java.util.Set.of(
            "ACCEPTED", "PROCESSING", "DEBOUNCED");
}
