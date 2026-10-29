package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.ai.application.*;
import com.ford.service.ai.configuration.AiProperties;
import com.ford.service.channels.adapter.out.JdbcInboundMessageDeduplicationAdapter;
import com.ford.service.channels.adapter.out.JdbcInboundMessagePersistenceAdapter;
import com.ford.service.channels.api.InboundMessageAccepted;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.conversations.adapter.out.*;
import com.ford.service.conversations.api.AssistantReply;
import com.ford.service.conversations.api.ConversationMemoryIndexRequested;
import com.ford.service.conversations.api.HandoffRequested;
import com.ford.service.conversations.application.*;
import com.ford.service.conversations.application.graph.ConversationGraphRoute;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.application.port.out.ShadowDraftRecorder;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryQuery;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.handoff.adapter.out.JdbcHandoffRepository;
import com.ford.service.handoff.api.HandoffTicketView;
import com.ford.service.handoff.domain.HandoffStatus;
import com.ford.service.handoffcontact.adapter.out.JdbcAlternateContactStagingRepository;
import com.ford.service.handoffcontact.api.AlternateContactValue;
import com.ford.service.handoffcontact.api.StagingContext;
import com.ford.service.handoffcontact.application.*;
import com.ford.service.leads.adapter.out.JdbcLeadJourneyRepository;
import com.ford.service.leads.application.LeadJourneyPolicy;
import com.ford.service.leads.application.LeadJourneyService;
import com.ford.service.realtime.application.InboundMessageRealtimeListener;
import com.ford.service.realtime.application.RealtimeEventHub;
import com.ford.service.realtime.domain.FrontendEvent;
import com.ford.service.shared.HumanHandoffPolicy;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real ingress and turn persistence; only the assistant response and event dispatch are test-local. */
@Testcontainers
class AlternateContactHandoffPrivacyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    @ParameterizedTest(name = "consented {0} stays reveal-only across handoff")
    @CsvSource({
            "PHONE, número, 55 1234 5678, +525512345678",
            "EMAIL, correo, synthetic.buyer@example.com, synthetic.buyer@example.com"
    })
    void movesConsentedContactFromEncryptedIngressToTicketWithoutLeaking(
            AlternateContactValue.ContactType type, String label, String raw, String normalized) throws Exception {
        var fixture = new Fixture();
        var message = new InboundMessage(fixture.tenant, Channel.WHATSAPP, "account-privacy",
                "privacy-" + UUID.randomUUID(), "synthetic-participant",
                "Quiero hablar con un asesor. Autorizo compartir mi " + label + " con Ford: " + raw,
                List.of(), Instant.now(), "meta:whatsapp:opaque", UUID.randomUUID(), UUID.randomUUID());

        var accepted = fixture.transaction.execute(status -> fixture.ingress.acceptAll(List.of(message)).getFirst());
        var event = (InboundMessageAccepted) fixture.events.getFirst();
        var context = new StagingContext(fixture.tenant, accepted.conversationId(), event.receiptId());
        assertPrivate(fixture.mapper.writeValueAsString(accepted), raw, normalized);
        assertThat(accepted.text()).contains("[dato de contacto omitido]");
        fixture.assertPrivateRows("channel_message", "conversation_id", context.conversationId(), raw, normalized);
        fixture.assertPrivateRows("conversation_message", "conversation_id", context.conversationId(), raw, normalized);
        assertThat(fixture.jdbc.sql("select count(*) from handoff_ticket where conversation_id=:id")
                .param("id", context.conversationId()).query(Integer.class).single()).isZero();

        var envelope = fixture.staging.find(fixture.tenant, context.conversationId(), context.receiptId(), Instant.now())
                .orElseThrow();
        assertThat(envelope.ciphertext()).isNotEmpty();
        assertPrivate(new String(envelope.ciphertext(), StandardCharsets.ISO_8859_1), raw, normalized);
        var staged = fixture.cipher.decrypt(envelope, context);
        assertThat(staged).singleElement().satisfies(contact -> {
            assertThat(contact.type()).isEqualTo(type);
            assertThat(contact.normalizedValue()).isEqualTo(normalized);
            assertThat(contact.consentMessageId()).isEqualTo(context.receiptId().toString());
        });

        // Explicit after-commit dispatch, as in production's transactional listener, without a Spring context.
        var realtime = new ArrayList<FrontendEvent>();
        try (var subscription = fixture.hub.subscribe(fixture.tenant, realtime::add)) {
            fixture.realtime.on(event);
        }
        assertThat(realtime).singleElement().satisfies(frontend ->
                assertThat(frontend.data()).containsEntry("text", accepted.text()));
        assertPrivate(fixture.mapper.writeValueAsString(realtime), raw, normalized);

        var reference = new ConversationTurnReference(fixture.tenant, context.conversationId(), context.receiptId());
        var outcome = fixture.transaction.execute(status -> fixture.processor.process(reference));
        assertThat(outcome.route()).isEqualTo(ConversationGraphRoute.HUMAN_PENDING);
        var ticket = new JdbcHandoffRepository(fixture.jdbc, fixture.mapper).findByConversation(context.conversationId());
        assertThat(ticket.status()).isEqualTo(HandoffStatus.HUMAN_PENDING);
        assertThat(ticket.packet().alternateContact()).contains(staged.getFirst());
        assertThat(fixture.staging.find(fixture.tenant, context.conversationId(), context.receiptId(), Instant.now())).isEmpty();
        assertThat(fixture.jdbc.sql("select count(*) from handoff_contact_staging where tenant_id=:tenant and receipt_id=:receipt")
                .param("tenant", fixture.tenant).param("receipt", context.receiptId()).query(Integer.class).single()).isZero();
        assertThat(fixture.jdbc.sql("select state from conversation where id=:id")
                .param("id", context.conversationId()).query(String.class).single()).isEqualTo("HUMAN_PENDING");
        assertThat(fixture.jdbc.sql("select state from conversation_turn where receipt_id=:id")
                .param("id", context.receiptId()).query(String.class).single()).isEqualTo("HUMAN_PENDING");

        var view = HandoffTicketView.from(ticket);
        assertThat(view.packet().keySet()).noneMatch(key -> key.startsWith("alternateContact")
                || key.equals("preferredContactChannel"));
        assertPrivate(fixture.mapper.writeValueAsString(view), raw, normalized);
        assertThat(fixture.events.stream().filter(HandoffRequested.class::isInstance).toList()).hasSize(1);
        assertThat(fixture.events.stream().filter(ConversationMemoryIndexRequested.class::isInstance)
                .map(ConversationMemoryIndexRequested.class::cast).toList()).singleElement().satisfies(index -> {
                    assertThat(index.message().content()).isEqualTo(accepted.text());
                    assertPrivate(index.message().content(), raw, normalized);
                });
        assertThat(fixture.jdbc.sql("select count(*) from conversation_decision "
                        + "where conversation_id=:conversation and provider_message_id=:provider")
                .param("conversation", context.conversationId()).param("provider", accepted.providerMessageId())
                .query(Integer.class).single()).isZero();
        assertPrivate(fixture.mapper.writeValueAsString(fixture.events), raw, normalized);
        assertThat(fixture.traces.list(fixture.tenant, context.conversationId(), context.receiptId())).isNotEmpty();
        for (var table : List.of("channel_message", "conversation_message",
                "conversation_decision_event", "conversation_turn")) {
            fixture.assertPrivateRows(table, "conversation_id", context.conversationId(), raw, normalized);
        }
        // Inbound recording persists conversation_message; a projection refresh is not requested on handoff.
        fixture.jdbc.sql("select to_jsonb(row)::text from conversation_memory row where conversation_id=:id")
                .param("id", context.conversationId()).query(String.class).list()
                .forEach(row -> assertPrivate(row, raw, normalized));
        fixture.assertPrivateRows("outbox_event", "aggregate_id", context.conversationId(), raw, normalized);
        assertPrivate(fixture.mapper.writeValueAsString(ticket.internalNote()), raw, normalized);
        var key = new ConversationMemoryKey(fixture.tenant, Channel.WHATSAPP, "account-privacy",
                "synthetic-participant", context.conversationId());
        var memory = fixture.memory.load(new MemoryQuery(key, "", null));
        assertPrivate(fixture.mapper.writeValueAsString(memory), raw, normalized);
    }

    private static void assertPrivate(String surface, String raw, String normalized) {
        assertThat(surface).doesNotContain(raw, normalized);
    }

    private static final class Fixture {
        final UUID tenant = UUID.randomUUID();
        final JdbcClient jdbc = JdbcClient.create(dataSource);
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        final List<Object> events = new ArrayList<>();
        final JdbcAlternateContactStagingRepository staging = new JdbcAlternateContactStagingRepository(jdbc);
        final AlternateContactStagingCipher cipher = new AlternateContactStagingCipher(
                new AlternateContactCryptoProperties(Base64.getEncoder().encodeToString(new byte[32]), 1, null, 0), mapper);
        final AlternateContactIntakeService intake = new AlternateContactIntakeService(
                new AlternateContactCapturePolicy(new AlternateContactRedactor()), cipher, staging, true);
        final ChannelMessageService ingress = new ChannelMessageService(new JdbcInboundMessageDeduplicationAdapter(jdbc),
                new JdbcInboundMessagePersistenceAdapter(jdbc, mapper), events::add, intake);
        final JdbcAcceptedInboundTurnRepository inbound = new JdbcAcceptedInboundTurnRepository(jdbc, mapper);
        final RealtimeEventHub hub = new RealtimeEventHub();
        final InboundMessageRealtimeListener realtime = new InboundMessageRealtimeListener(hub, inbound, intake);
        final MemoryProperties properties = new MemoryProperties();
        final JdbcConversationMemoryRepository durableMemory = new JdbcConversationMemoryRepository(jdbc, mapper, properties);
        final ConversationMemoryService memory = new ConversationMemoryService(durableMemory, properties,
                new NoOpConversationMemorySemanticPort(), events::add);
        final JdbcDecisionTraceRepository traces = new JdbcDecisionTraceRepository(jdbc);
        final DefaultConversationTurnProcessor processor;

        Fixture() {
            var conversations = new JdbcConversationRepository(jdbc, mapper);
            var journeys = new LeadJourneyService(new JdbcLeadJourneyRepository(jdbc, mapper), new LeadJourneyPolicy());
            var commit = new ConversationTurnCommitService(conversations, new JdbcConversationDecisionRepository(jdbc),
                    journeys, new HumanHandoffPolicy(true), memory, events::add,
                    new JdbcConversationTurnLedgerAdapter(jdbc), traces, staging);
            var providers = new ModelProviderPolicy() {
                public ProviderDescriptor select(AiExecutionContext context, ModelRequirements requirements) {
                    throw new AssertionError("Text ingress must not invoke a media provider");
                }
            };
            var media = new InboundContentResolutionService(new AiProperties(), providers,
                    (t, id, max) -> null, (p, k, r, m, c) -> "", Runnable::run);
            processor = new DefaultConversationTurnProcessor(inbound, conversations, memory, journeys, request -> {
                throw new AssertionError("a direct customer request must bypass assistant execution");
            }, commit, media, null, ShadowDraftRecorder.disabled(), ShadowReviewGate.disabled(), true, intake);
        }

        void assertPrivateRows(String table, String scopeColumn, UUID scope, String raw, String normalized) {
            var rows = jdbc.sql("select to_jsonb(row)::text from " + table + " row where " + scopeColumn + "=:id")
                    .param("id", scope).query(String.class).list();
            assertThat(rows).as(table + " must have observable persisted rows").isNotEmpty();
            rows.forEach(row -> assertPrivate(row, raw, normalized));
        }
    }
}
