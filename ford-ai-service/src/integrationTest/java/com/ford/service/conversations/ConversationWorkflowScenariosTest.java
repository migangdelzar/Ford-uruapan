package com.ford.service.conversations;

import com.ford.service.ai.application.*;
import com.ford.service.ai.application.port.out.ChatCompletionPort;
import com.ford.service.ai.api.AssistantRequest;
import com.ford.service.ai.api.AssistantResponse;
import com.ford.service.channels.domain.Channel;
import com.ford.service.conversations.adapter.out.JdbcConversationDecisionRepository;
import com.ford.service.conversations.adapter.out.JdbcConversationTurnLedgerAdapter;
import com.ford.service.conversations.adapter.out.JdbcConversationRepository;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.adapter.out.RedisCachedConversationMemoryPort;
import com.ford.service.conversations.adapter.out.RedisConversationMemoryCache;
import com.ford.service.conversations.application.ConversationAssistantAdapter;
import com.ford.service.conversations.application.ConversationMemoryPort;
import com.ford.service.conversations.application.ConversationMemoryService;
import com.ford.service.conversations.application.ConversationTurnCommitService;
import com.ford.service.conversations.application.DefaultConversationTurnProcessor;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.application.PendingTestDriveReplyPolicy;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.domain.*;
import com.ford.service.leads.adapter.out.JdbcLeadJourneyRepository;
import com.ford.service.leads.application.LeadJourneyPolicy;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.leads.domain.LeadJourneyProposal;
import com.ford.service.leads.domain.LeadJourneySnapshot;
import com.ford.service.leads.domain.LeadJourneyTopic;
import com.ford.service.shared.HumanHandoffPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Multi-turn synthetic sales paths using production PostgreSQL repositories and workflow services. */
@Testcontainers
@ActiveProfiles("test")
class ConversationWorkflowScenariosTest {

    @Container static final PostgreSQLContainer<?> POSTGRES = new com.ford.service.database.FordPostgresContainer();
    @Container static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @BeforeAll static void migrate() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        var liquibaseRunner = new SpringLiquibase(); liquibaseRunner.setDataSource(source);
        liquibaseRunner.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml"); liquibaseRunner.afterPropertiesSet();
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test void continuesTestDriveThenCustomerSwitchesToFinanceWithMemoryAndDecisionEvidence() {
        var scenario = new Scenario();
        var first = scenario.turn("Quiero conocer la Bronco", DecisionKind.JEV_VEHICLE,
                "La Bronco Base 2026 está en catálogo. ¿Le gustaría agendar una prueba de manejo?");
        assertThat(first.reply()).contains("Bronco Base"); assertThat(scenario.toolCalls).containsExactly("get_vehicle");
        var second = scenario.turn("Sí, me gustaría", null, null);
        assertThat(second.reply()).isEqualTo("¡Perfecto! ¿Qué día le conviene para la prueba de manejo?");
        assertThat(second.journey().currentTopic()).isEqualTo(LeadJourneyTopic.TEST_DRIVE_APPOINTMENT);
        assertThat(second.journey().pendingQuestionKey()).isEqualTo("TEST_DRIVE_DATE");
        assertThat(second.decision().intent()).isEqualTo(ConversationIntent.APPOINTMENT);
        assertThat(second.decision().evidence()).allSatisfy(e -> assertThat(e.fallbackUsed()).isTrue());
        assertThat(scenario.toolCalls).containsExactly("get_vehicle"); // booking tool is not exposed
        var third = scenario.turn("Mejor dime la mensualidad", DecisionKind.RULES_FINANCE,
                "Puedo estimarla. ¿Qué enganche considerarías?");
        assertThat(third.memory()).contains("Sí, me gustaría", "prueba de manejo");
        assertThat(third.journey().currentTopic()).isEqualTo(LeadJourneyTopic.FINANCING_TRADE_IN);
        assertThat(third.journey().pausedTopics()).contains(LeadJourneyTopic.TEST_DRIVE_APPOINTMENT);
        assertThat(third.classifierFallback()).isTrue();
        assertThat(scenario.outbound()).hasSize(3);
    }

    @Test void followsOffersThenVehiclePathWithoutReusingWrongTool() {
        var scenario = new Scenario();
        scenario.turn("¿Qué promociones tienen para Ranger?", DecisionKind.JEV_OFFERS,
                "Encontré una promoción vigente para Ranger. ¿Qué versión te interesa?");
        var next = scenario.turn("La XLT", DecisionKind.JEV_VEHICLE,
                "La Ranger XLT coincide con lo que buscas. ¿Quieres revisar financiamiento?");
        assertThat(next.memory()).contains("promociones", "Ranger");
        assertThat(scenario.toolCalls).containsExactly("search_offers", "get_vehicle");
        assertThat(scenario.outbound()).hasSize(2);
    }

    @Test void reviewFailureAtomicallyPersistsSafeFallbackOutboxAndDurableReview() {
        var scenario = new Scenario();
        var receipt = UUID.randomUUID();
        var inbound = new com.ford.service.channels.api.AcceptedInboundMessage(
                "synthetic-review-in", "synthetic", "¿Cuál es el precio?", Instant.now());
        var assistant = (com.ford.service.conversations.application.port.out.ConversationAssistant)
                request -> { throw new com.ford.service.ai.api.AssistantTurnUnavailableException(
                        com.ford.service.ai.api.AssistantTurnUnavailableException.Disposition.REVIEW); };
        var providerPolicy = new ModelProviderPolicy() {
            public ProviderDescriptor select(AiExecutionContext context, ModelRequirements requirements) {
                return new ProviderDescriptor("groq", new ProviderCapabilities(true, true, false), true, true,
                        "gpt-oss-20b");
            }
        };
        var media = new InboundContentResolutionService(new com.ford.service.ai.configuration.AiProperties(),
                providerPolicy, (t, id, max) -> null, (p, k, r, m, c) -> "", Runnable::run);
        var commit = new ConversationTurnCommitService(scenario.conversations,
                new JdbcConversationDecisionRepository(scenario.jdbc), scenario.journeys,
                new HumanHandoffPolicy(false), scenario.memory, event -> { },
                new JdbcConversationTurnLedgerAdapter(scenario.jdbc));
        var processor = new DefaultConversationTurnProcessor((tenant, conversation, id) -> Optional.of(inbound),
                scenario.conversations, scenario.memory, scenario.journeys, assistant, commit, media);

        var result = processor.process(new ConversationTurnReference(scenario.tenant, scenario.conversation, receipt));

        assertThat(result.route()).isEqualTo(ConversationGraphRoute.SAFE_FALLBACK_REVIEW);
        assertThat(scenario.jdbc.sql("select state from conversation_turn where tenant_id=:tenant and receipt_id=:receipt")
                .param("tenant", scenario.tenant).param("receipt", receipt).query(String.class).single())
                .isEqualTo("SAFE_FALLBACK_REVIEW");
        assertThat(scenario.jdbc.sql("select count(*) from conversation_review_item where tenant_id=:tenant and receipt_id=:receipt and status='PENDING'")
                .param("tenant", scenario.tenant).param("receipt", receipt).query(Integer.class).single()).isEqualTo(1);
        assertThat(scenario.jdbc.sql("select content from conversation_message where conversation_id=:conversation and direction='OUTBOUND'")
                .param("conversation", scenario.conversation).query(String.class).single())
                .contains("No pude confirmar esa información");
        assertThat(scenario.jdbc.sql("select count(*) from outbox_event where aggregate_id=:conversation and event_type='OUTBOUND_DELIVERY_REQUESTED'")
                .param("conversation", scenario.conversation).query(Integer.class).single()).isEqualTo(1);
        scenario.jdbc.sql("delete from outbox_event where event_type='OUTBOUND_DELIVERY_REQUESTED' and aggregate_id<>:conversation")
                .param("conversation", scenario.conversation).update();
        var outbox = new com.ford.service.outbox.JdbcOutboxStore(scenario.jdbc);
        var delivery = new com.ford.service.outbox.OutboxPublisher(outbox,
                event -> com.ford.service.outbox.NotificationResult.delivered("synthetic-provider-receipt"), 3);
        assertThat(delivery.deliverOne(Instant.now())).isTrue();
        assertThat(scenario.jdbc.sql("select status from outbox_event where aggregate_id=:conversation and event_type='OUTBOUND_DELIVERY_REQUESTED'")
                .param("conversation", scenario.conversation).query(String.class).single()).isEqualTo("DELIVERED");
        assertThat(scenario.jdbc.sql("select provider_delivery_id from outbox_event where aggregate_id=:conversation and event_type='OUTBOUND_DELIVERY_REQUESTED'")
                .param("conversation", scenario.conversation).query(String.class).single())
                .isEqualTo("synthetic-provider-receipt");
    }

    @Test void ledgerFailureRollsBackFallbackTranscriptAndOutboxTogether() {
        var scenario = new Scenario();
        var receipt = UUID.randomUUID();
        var inbound = new com.ford.service.channels.api.AcceptedInboundMessage(
                "synthetic-ledger-failure", "synthetic", "¿Cuál es el precio?", Instant.now());
        var reference = new ConversationTurnReference(scenario.tenant, scenario.conversation, receipt);
        var snapshot = scenario.conversations.find(scenario.conversation).orElseThrow();
        var memoryKey = new ConversationMemoryKey(scenario.tenant, Channel.WHATSAPP,
                "phone-1", "synthetic", scenario.conversation);
        var delegate = new JdbcConversationTurnLedgerAdapter(scenario.jdbc);
        var ledger = new com.ford.service.conversations.application.port.out.ConversationTurnLedgerPort() {
            @Override
            public void recordOutcome(UUID tenantId, UUID conversationId, UUID receiptId, String providerMessageId,
                                      com.ford.service.conversations.application.ConversationTurnState state,
                                      String failureCategory, Instant occurredAt) {
                delegate.recordOutcome(tenantId, conversationId, receiptId, providerMessageId,
                        state, failureCategory, occurredAt);
                if (state == com.ford.service.conversations.application.ConversationTurnState.SAFE_FALLBACK_REVIEW) {
                    throw new IllegalStateException("synthetic ledger write failure");
                }
            }

            @Override
            public void recordReview(UUID tenantId, UUID conversationId, UUID receiptId,
                                     String reasonCode, Instant createdAt) {
                delegate.recordReview(tenantId, conversationId, receiptId, reasonCode, createdAt);
            }
        };
        var service = new ConversationTurnCommitService(scenario.conversations,
                new JdbcConversationDecisionRepository(scenario.jdbc), scenario.journeys,
                new HumanHandoffPolicy(false), scenario.memory, event -> { }, ledger);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(scenario.dataSource));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.execute(status ->
                        service.commitSafeFallback(reference, inbound, snapshot,
                                ConversationMemoryContext.empty(memoryKey),
                                new LeadJourneySnapshot(scenario.tenant, scenario.conversation, null, null, null, null,
                                        null, null, 0, null, 0, Instant.now()),
                                "No pude confirmar esa información ahora. El equipo de Ford te ayudará en breve.",
                                "invalid_model_output")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.jdbc.sql("select count(*) from conversation_message where conversation_id=:conversation and direction='OUTBOUND'")
                .param("conversation", scenario.conversation).query(Integer.class).single()).isZero();
        assertThat(scenario.jdbc.sql("select count(*) from outbox_event where aggregate_id=:conversation and event_type='OUTBOUND_DELIVERY_REQUESTED'")
                .param("conversation", scenario.conversation).query(Integer.class).single()).isZero();
        assertThat(scenario.jdbc.sql("select count(*) from conversation_turn where receipt_id=:receipt")
                .param("receipt", receipt).query(Integer.class).single()).isZero();
    }

    @Test void decisionTraceFailureRollsBackTurnOutcomeAndTraceTogether() {
        var scenario = new Scenario();
        var receipt = UUID.randomUUID();
        var inbound = new com.ford.service.channels.api.AcceptedInboundMessage(
                "synthetic-trace-failure", "synthetic", "¿Cuál es el precio?", Instant.now());
        var reference = new ConversationTurnReference(scenario.tenant, scenario.conversation, receipt);
        var snapshot = scenario.conversations.find(scenario.conversation).orElseThrow();
        var memoryKey = new ConversationMemoryKey(scenario.tenant, Channel.WHATSAPP,
                "phone-1", "synthetic", scenario.conversation);
        var traceDelegate = new com.ford.service.conversations.adapter.out.JdbcDecisionTraceRepository(scenario.jdbc);
        var traceRepository = new com.ford.service.conversations.application.DecisionTraceRepository() {
            @Override public void append(UUID tenantId, UUID conversationId, UUID receiptId,
                                         List<com.ford.service.ai.application.DecisionTraceEvent> events) {
                traceDelegate.append(tenantId, conversationId, receiptId, events);
                throw new IllegalStateException("synthetic trace persistence failure");
            }
            @Override public List<com.ford.service.ai.application.DecisionTraceEvent> list(
                    UUID tenantId, UUID conversationId, UUID receiptId) {
                return traceDelegate.list(tenantId, conversationId, receiptId);
            }
        };
        var service = new ConversationTurnCommitService(scenario.conversations,
                new JdbcConversationDecisionRepository(scenario.jdbc), scenario.journeys,
                new HumanHandoffPolicy(false), scenario.memory, event -> { },
                new JdbcConversationTurnLedgerAdapter(scenario.jdbc), traceRepository);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(scenario.dataSource));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.execute(status ->
                        service.commitSafeFallback(reference, inbound, snapshot, ConversationMemoryContext.empty(memoryKey),
                                new LeadJourneySnapshot(scenario.tenant, scenario.conversation, null, null, null, null,
                                        null, null, 0, null, 0, Instant.now()),
                                "No pude confirmar esa información ahora. El equipo de Ford te ayudará en breve.",
                                "invalid_model_output")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.jdbc.sql("select count(*) from conversation_message where conversation_id=:conversation and direction='OUTBOUND'")
                .param("conversation", scenario.conversation).query(Integer.class).single()).isZero();
        assertThat(scenario.jdbc.sql("select count(*) from conversation_turn where receipt_id=:receipt")
                .param("receipt", receipt).query(Integer.class).single()).isZero();
        assertThat(scenario.jdbc.sql("select count(*) from conversation_decision_event where receipt_id=:receipt")
                .param("receipt", receipt).query(Integer.class).single()).isZero();
    }

    @Test void handlesDeclineThenSwitchesToAnotherVehicle() {
        var scenario = new Scenario();
        scenario.turn("Busco una Bronco", DecisionKind.JEV_VEHICLE,
                "La Bronco está disponible en catálogo. ¿Te interesa una prueba de manejo?");
        scenario.turn("No gracias", DecisionKind.RULES_GENERAL, "Entendido, sin problema.");
        var next = scenario.turn("¿Tienen Maverick?", DecisionKind.JEV_VEHICLE,
                "Puedo revisar Maverick. ¿Qué versión buscas?");
        assertThat(next.memory()).contains("No gracias", "Bronco");
        assertThat(next.journey().currentTopic()).isEqualTo(LeadJourneyTopic.VEHICLE_DISCOVERY);
        assertThat(scenario.toolCalls).containsExactly("get_vehicle", "get_vehicle");
        assertThat(scenario.outbound()).hasSize(3);
    }

    private enum DecisionKind { JEV_VEHICLE, JEV_OFFERS, RULES_FINANCE, RULES_GENERAL }
    private record TurnResult(String reply, ConversationDecision decision, String memory,
                              LeadJourneySnapshot journey, boolean classifierFallback) { }

    private static LeadJourneyProposal proposal(DecisionKind kind, String answer) {
        if (kind == null) return null;
        if (answer != null && answer.toLowerCase(Locale.ROOT).contains("prueba de manejo"))
            return new LeadJourneyProposal(LeadJourneyTopic.TEST_DRIVE_APPOINTMENT,List.of(),"TEST_DRIVE_CONFIRMATION");
        if (kind == DecisionKind.JEV_OFFERS)
            return new LeadJourneyProposal(LeadJourneyTopic.PROMOTIONS_PRICING,List.of(),"VEHICLE_MODEL");
        if (kind == DecisionKind.RULES_FINANCE)
            return new LeadJourneyProposal(LeadJourneyTopic.FINANCING_TRADE_IN,List.of(),"DOWN_PAYMENT");
        return new LeadJourneyProposal(LeadJourneyTopic.VEHICLE_DISCOVERY,List.of(),"VEHICLE_MODEL");
    }

    private static final class Scenario {
        final UUID tenant = UUID.randomUUID(), conversation = UUID.randomUUID();
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        final JdbcClient jdbc = JdbcClient.create(dataSource);
        final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        final JdbcConversationRepository conversations = new JdbcConversationRepository(jdbc, mapper);
        final JdbcLeadJourneyRepository journeyRepository = new JdbcLeadJourneyRepository(jdbc, mapper);
        final MemoryProperties memoryProperties = new MemoryProperties();
        final JdbcConversationMemoryRepository durableMemory = new JdbcConversationMemoryRepository(jdbc, mapper, memoryProperties);
        final LettuceConnectionFactory redisFactory;
        final RedisCachedConversationMemoryPort cachedMemory;
        final ConversationMemoryService memory;
        final LeadJourneyService journeys = new LeadJourneyService(journeyRepository, new LeadJourneyPolicy());
        final List<String> toolCalls = new ArrayList<>();
        int turn;
        Scenario() {
            redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
            redisFactory.afterPropertiesSet();
            var redis = new StringRedisTemplate(redisFactory);
            redis.afterPropertiesSet();
            var cache = new RedisConversationMemoryCache(redis, mapper, memoryProperties, new SimpleMeterRegistry());
            cachedMemory = new RedisCachedConversationMemoryPort(durableMemory, cache, memoryProperties,
                    new SimpleMeterRegistry());
            memory = new ConversationMemoryService(cachedMemory, memoryProperties);
            var now = Timestamp.from(Instant.now());
            jdbc.sql("insert into conversation(id,tenant_id,channel,provider_conversation_id,provider_account_id,participant_id,state,version,created_at,updated_at) values (:id,:tenant,'WHATSAPP','wa','phone-1','synthetic','BOT_ACTIVE',0,:now,:now)")
                    .param("id", conversation).param("tenant", tenant).param("now", now).update();
        }
        TurnResult turn(String text, DecisionKind kind, String generated) {
            turn++; var receipt = UUID.randomUUID(); var providerMessageId = "synth-in-" + turn;
            var inbound = new com.ford.service.channels.api.AcceptedInboundMessage(providerMessageId,"synthetic",text,Instant.now());
            var memoryKey = new ConversationMemoryKey(tenant,Channel.WHATSAPP,"phone-1","synthetic",conversation);
            var rules = new com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter(new IntentRouter());
            var classifier = new ScenarioClassifier(kind); var classificationProperties = new com.ford.service.ai.configuration.ClassificationProperties();
            var decisions = new ConversationDecisionService(classifier,rules,classificationProperties);
            var providerPolicy = new ModelProviderPolicy() {
                public ProviderDescriptor select(AiExecutionContext context,ModelRequirements requirements) {
                    return new ProviderDescriptor("groq",new ProviderCapabilities(true,true,false),true,true,"gpt-oss-20b");
                }
            };
            var chat = new ChatCompletionPort() {
                public AssistantResponse complete(com.ford.service.ai.api.AssistantRequest request,ToolContext context) {
                    var available = List.of("get_vehicle", "search_offers", "finance_estimate", "book_appointment").stream()
                            .map(name -> callback(name, toolCalls)).toList();
                    var toolPolicy = new ToolAuthorizationPolicy(available);
                    var names = toolPolicy.availableReadOnlyToolNames();
                    var permitted = new DecisionPolicy().allowedToolNames(request.decision(), names);
                    if (request.decision().toolFamily()==ToolFamily.APPOINTMENT) {
                        assertThat(permitted).isEmpty(); assertThat(generated).isNull();
                        return new AssistantResponse("¡Perfecto! ¿Qué día le conviene para la prueba de manejo?",false,"groq",List.of());
                    }
                    var expected = switch(request.decision().toolFamily()) {
                        case VEHICLE_DETAILS -> "get_vehicle"; case OFFERS -> "search_offers";
                        case FINANCE_ESTIMATE -> "finance_estimate"; default -> null;
                    };
                    if(expected!=null) {
                        assertThat(permitted).contains(expected);
                        var callbacks = toolPolicy.readOnlyTools(new AiExecutionContext(tenant,false,conversation,
                                request.memoryContext(),request.decision()), permitted);
                        assertThat(callbacks).filteredOn(callback ->
                                        expected.equals(callback.getToolDefinition().name()))
                                .singleElement().satisfies(callback -> {
                            assertThat(callback.getToolDefinition().name()).isEqualTo(expected);
                            callback.call("{}");
                        });
                    }
                    assertThat(generated).isNotNull(); return new AssistantResponse(generated,false,"groq",List.of(),proposal(kind,generated));
                }
            };
            var assistantService = new SpringAiAssistantService(new DefaultAssistantOrchestrator(new IntentRouter(),new ToolAuthorizationPolicy(),chat),
                    new HumanHandoffPolicy(false),providerPolicy,false,decisions,new DecisionPolicy(),new AssistantScopePolicy(new IntentRouter()),
                    new LeadQualificationPolicy(),new InputSecurityPolicy(classificationProperties));
            var assistant = new ConversationAssistantAdapter(assistantService,new PendingTestDriveReplyPolicy());
            var proposal = proposal(kind, generated);
            var media = new InboundContentResolutionService(new com.ford.service.ai.configuration.AiProperties(),providerPolicy,
                    (t,id,max)->null,(p,k,r,m,c)->"",Runnable::run);
            var processor = new DefaultConversationTurnProcessor((t,c,r)->Optional.of(inbound),conversations,memory,journeys,assistant,
                     new ConversationTurnCommitService(conversations,new JdbcConversationDecisionRepository(jdbc),journeys,
                             new HumanHandoffPolicy(false),memory,event -> {
                                 if (event instanceof com.ford.service.conversations.api.ConversationMemoryProjectionRefreshRequested refresh) {
                                     durableMemory.updateProjection(refresh.key(), refresh.update(), refresh.expectedVersion());
                                 }
                               }, new JdbcConversationTurnLedgerAdapter(jdbc),
                              new com.ford.service.conversations.adapter.out.JdbcDecisionTraceRepository(jdbc)),media);
            assertThat(processor.process(new ConversationTurnReference(tenant,conversation,receipt)).route()).isEqualTo(ConversationGraphRoute.BOT_REPLY);
            assertThat(jdbc.sql("select state from conversation_turn where tenant_id=:tenant and conversation_id=:conversation and receipt_id=:receipt")
                    .param("tenant", tenant).param("conversation", conversation).param("receipt", receipt)
                    .query(String.class).single()).isEqualTo("REPLY_COMMITTED");
            var context=durableMemory.load(new MemoryQuery(memoryKey,text,providerMessageId));
            var currentJourney=journeys.load(tenant,conversation);
            var reply=outbound().getLast();
             var rows=jdbc.sql("select count(*) from conversation_decision where conversation_id=:c and provider_message_id=:m")
                     .param("c",conversation).param("m",providerMessageId).query(Integer.class).single(); assertThat(rows).isGreaterThan(0);
             assertThat(jdbc.sql("select count(*) from conversation_decision_event where tenant_id=:tenant and conversation_id=:c and receipt_id=:receipt")
                     .param("tenant", tenant).param("c",conversation).param("receipt",receipt)
                     .query(Integer.class).single()).isGreaterThan(0);
            if (kind == DecisionKind.JEV_VEHICLE) {
                assertThat(jdbc.sql("select count(*) from conversation_decision where conversation_id=:c and provider_message_id=:m and provider='typesafe' and fallback_used=false")
                        .param("c",conversation).param("m",providerMessageId).query(Integer.class).single()).isGreaterThan(0);
            }
            if (kind == DecisionKind.JEV_OFFERS && new IntentRouter().isExplicitFordFactRequest(text)) {
                assertThat(jdbc.sql("select count(*) from conversation_decision where conversation_id=:c and provider_message_id=:m and provider='typesafe'")
                        .param("c",conversation).param("m",providerMessageId).query(Integer.class).single()).isZero();
                assertThat(jdbc.sql("select count(*) from conversation_decision where conversation_id=:c and provider_message_id=:m and provider='rules' and fallback_used=true")
                        .param("c",conversation).param("m",providerMessageId).query(Integer.class).single()).isGreaterThan(0);
            }
            if (kind == DecisionKind.RULES_FINANCE || kind == DecisionKind.RULES_GENERAL) {
                assertThat(jdbc.sql("select count(*) from conversation_decision where conversation_id=:c and provider_message_id=:m and provider='rules' and fallback_used=true")
                        .param("c",conversation).param("m",providerMessageId).query(Integer.class).single()).isGreaterThan(0);
            }
            var decision=classifier.last==null?syntheticAppointment(text):classifier.last;
            return new TurnResult(reply,decision,render(context),currentJourney,decision.evidence().stream().anyMatch(e->e.fallbackUsed()));
        }
        List<String> outbound(){return jdbc.sql("select content from conversation_message where conversation_id=:id and direction='OUTBOUND' order by received_at")
                .param("id",conversation).query(String.class).list();}
        static String render(ConversationMemoryContext c){var b=new StringBuilder(c.summary());c.recentMessages().forEach(m->b.append('\n').append(m.content()));return b.toString();}
        static ConversationDecision syntheticAppointment(String message){return new ConversationDecision(ConversationIntent.APPOINTMENT,LeadTemperature.HOT,LeadStage.QUALIFIED,
                HandoffRecommendation.NONE,ToolFamily.APPOINTMENT,SensitiveDataLevel.NONE,LeadNextAction.SCHEDULE_TEST_DRIVE,true,message,
                List.of(new ClassificationEvidence(ClassifierType.INTENT,"APPOINTMENT",1,"pending_question_confirmation","rules","deterministic",0,true)));}
    }
    private static org.springframework.ai.tool.ToolCallback callback(String name, List<String> calls) {
        return new org.springframework.ai.tool.ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description(name).inputSchema("{}").build();
            }
            @Override public String call(String input) { calls.add(name); return "synthetic read-only result"; }
        };
    }

    private static final class ScenarioClassifier implements ConversationDecisionPort {
        final DecisionKind kind; ConversationDecision last;
        ScenarioClassifier(DecisionKind kind){this.kind=kind;}
        public ConversationDecision decide(ClassificationContext context){
            if(kind==null) throw new AssertionError("affirmative to pending offer must bypass classifier");
            if(kind==DecisionKind.RULES_FINANCE||kind==DecisionKind.RULES_GENERAL){last=new com.ford.service.ai.adapter.out.rules.RulesConversationDecisionAdapter(new IntentRouter()).decide(context);return last;}
            var intent=kind==DecisionKind.JEV_OFFERS?ConversationIntent.OFFERS:ConversationIntent.VEHICLE_DETAILS;
            var family=kind==DecisionKind.JEV_OFFERS?ToolFamily.OFFERS:ToolFamily.VEHICLE_DETAILS;
            last=new ConversationDecision(intent,LeadTemperature.WARM,LeadStage.QUALIFYING,HandoffRecommendation.NONE,family,SensitiveDataLevel.NONE,
                    LeadNextAction.FOLLOW_UP,false,context.message(),List.of(new ClassificationEvidence(ClassifierType.INTENT,intent.name(),.98,"model_choice","typesafe","jev-synthetic",1,false),
                    new ClassificationEvidence(ClassifierType.TOOL_FAMILY,family.name(),.98,"model_choice","typesafe","jev-synthetic",1,false)));return last;
        }
    }
}
