package com.ford.service.conversations;

import com.ford.service.database.FordPostgresContainer;
import com.ford.service.conversations.adapter.out.JdbcMessageApprovalRepository;
import com.ford.service.conversations.adapter.out.JdbcConversationRepository;
import com.ford.service.conversations.application.port.out.MessageApprovalRepository;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import com.ford.service.channels.adapter.out.JdbcInboundMessageDeduplicationAdapter;
import com.ford.service.channels.adapter.out.JdbcInboundMessagePersistenceAdapter;
import com.ford.service.channels.application.ChannelBindingService;
import com.ford.service.channels.application.ChannelMessageService;
import com.ford.service.channels.application.InboundWebhookService;
import com.ford.service.channels.application.port.in.ChannelAdapter;
import com.ford.service.channels.api.ChannelBinding;
import com.ford.service.conversations.domain.MessageApproval;
import com.ford.service.conversations.domain.ConversationTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.adapter.out.JdbcInboundMessagePersistenceAdapter;
import com.ford.service.channels.domain.Channel;
import com.ford.service.channels.domain.InboundMessage;
import com.ford.service.channels.adapter.out.JdbcCustomerWindowAdapter;
import com.ford.service.conversations.adapter.out.JdbcAcceptedInboundTurnRepository;
import com.ford.service.conversations.adapter.out.JdbcMessageApprovalSendValidationAdapter;
import com.ford.service.conversations.application.MessageApprovalIntakeService;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import com.ford.service.conversations.domain.AdvisorResponseDisposition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcMessageApprovalRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T16:30:00Z");
    @Container static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();
    static JdbcClient jdbc;
    static DriverManagerDataSource dataSource;
    static TransactionTemplate transactions;
    static JdbcMessageApprovalRepository repository;
    static JdbcConversationRepository conversations;
    static JdbcMessageApprovalSendValidationAdapter sendValidation;

    @BeforeAll static void migrate() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var liquibase = new liquibase.integration.spring.SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcMessageApprovalRepository(jdbc);
        conversations = new JdbcConversationRepository(jdbc, new ObjectMapper());
        sendValidation = new JdbcMessageApprovalSendValidationAdapter(jdbc,
                new JdbcAcceptedInboundTurnRepository(jdbc, new ObjectMapper()),
                new JdbcCustomerWindowAdapter(jdbc), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @Test void sendValidationRequiresCurrentSendableDraftAndGroundedReplacement() {
        var fixture = fixture();
        var approval = transactions.execute(tx -> repository.savePending(fixture.approval()));

        var state = jdbc.sql("select a.status,a.conversation_version,c.version,c.state,coalesce(b.enabled,false), (select m.id from channel_message m where m.tenant_id=c.tenant_id and m.conversation_id=c.id order by m.received_at desc,m.id desc limit 1) from message_approval a join conversation c on c.id=a.conversation_id left join channel_binding b on b.tenant_id=c.tenant_id and b.channel=c.channel and b.provider_account_id=c.provider_account_id where a.id=:approval")
                .param("approval", approval.id()).query((rs, row) -> java.util.List.of(rs.getString(1), rs.getLong(2),
                        rs.getLong(3), rs.getString(4), rs.getBoolean(5), rs.getObject(6, UUID.class))).single();
        assertThat(state).containsExactly("PENDING", 8L, 8L, "BOT_ACTIVE", true, fixture.receipt());

        transactions.executeWithoutResult(tx -> sendValidation.validate(approval,
                MessageApproval.Action.APPROVE, approval.draftText()));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> sendValidation.validate(approval,
                MessageApproval.Action.EDIT, "La Bronco Sport cuesta $700,000.")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void approveCannotBypassUnsupportedCommitmentGateEvenForSendableStoredDraft() {
        var fixture = fixture();
        var disposition = new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.REVIEW,
                AdvisorResponseDisposition.Reason.DISCOUNT_COMMITMENT);
        var unsafe = MessageApproval.pending(UUID.randomUUID(), fixture.tenant(), fixture.conversation(),
                fixture.receipt(), 7, disposition, "Te aplicamos un descuento de $5,000.", true, NOW);
        var persisted = transactions.execute(tx -> repository.savePending(unsafe));

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> sendValidation.validate(persisted,
                MessageApproval.Action.APPROVE, persisted.draftText())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void creatingHoldAdvancesConversationVersionAndBlocksAnInflightBotCommit() {
        var fixture = fixture();
        var persisted = transactions.execute(tx -> repository.savePending(fixture.approval()));

        assertThat(persisted.conversationVersion()).isEqualTo(8);
        assertThat(conversations.completeBotTurn(fixture.tenant(), fixture.conversation(), 7,
                "inflight-message", new ConversationTurn("stale bot reply"))).isFalse();
        assertThat(conversations.completeBotTurn(fixture.tenant(), fixture.conversation(), 8,
                "fresh-but-held-message", new ConversationTurn("reply while held"))).isFalse();
        assertThat(jdbc.sql("select version from conversation where id=:id").param("id", fixture.conversation())
                .query(Long.class).single()).isEqualTo(8L);
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id")
                .param("id", fixture.conversation()).query(Integer.class).single()).isZero();
    }

    @Test void productionIntakePersistsMandatoryHoldWithoutDraftAndAdvancesVersion() {
        var fixture = fixture();
        var snapshot = conversations.find(fixture.tenant(), fixture.conversation()).orElseThrow();
        var intake = new MessageApprovalIntakeService(repository, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        var disposition = new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.MANDATORY_HANDOFF,
                AdvisorResponseDisposition.Reason.HUMAN_REQUEST);

        var held = transactions.execute(tx -> intake.hold(new ConversationTurnReference(fixture.tenant(),
                fixture.conversation(), fixture.receipt()), snapshot, disposition, null, false));

        assertThat(held.status()).isEqualTo(MessageApproval.Status.PENDING);
        assertThat(held.draftText()).isNull();
        assertThat(held.allowedActions()).containsExactly(MessageApproval.Action.TAKEOVER);
        assertThat(held.conversationVersion()).isEqualTo(8);
        assertThat(jdbc.sql("select count(*) from message_approval_hold where approval_id=:id")
                .param("id", held.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void failedAdvisorReviewCountsTowardRepeatedInabilityEscalation() {
        var fixture = fixture();
        var approval = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var ledger = new com.ford.service.conversations.adapter.out.JdbcConversationTurnLedgerAdapter(jdbc);
        transactions.executeWithoutResult(tx -> ledger.recordOutcome(fixture.tenant(), fixture.conversation(),
                fixture.receipt(), fixture.receipt().toString(),
                com.ford.service.conversations.application.ConversationTurnState.NEEDS_OPERATOR,
                "advisor_review", NOW.plusSeconds(1)));

        assertThat(approval.disposition().reason()).isEqualTo(AdvisorResponseDisposition.Reason.RESPONSE_REVIEW);
        assertThat(ledger.consecutiveSubstantiveFailures(fixture.tenant(), fixture.conversation())).isEqualTo(1);
    }

    @Test void acceptedWebhookInvalidatesApprovalBeforeReleasedTurnCanRun() {
        var fixture = fixture();
        var approval = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var bindings = org.mockito.Mockito.mock(ChannelBindingService.class);
        var adapter = org.mockito.Mockito.mock(ChannelAdapter.class);
        var binding = new ChannelBinding(fixture.bindingId(), fixture.tenant(), Channel.WHATSAPP,
                fixture.account(), "secret-ref", true);
        var latest = new InboundMessage(fixture.tenant(), Channel.WHATSAPP, fixture.account(),
                "provider-next", fixture.conversation().toString(), "Siguiente pregunta", java.util.List.of(),
                NOW.plusSeconds(2), "test-payload", fixture.bindingId(), fixture.conversation());
        org.mockito.Mockito.when(adapter.providerAccountIds("webhook-body"))
                .thenReturn(java.util.List.of(fixture.account()));
        org.mockito.Mockito.when(bindings.resolve(Channel.WHATSAPP, fixture.account())).thenReturn(binding);
        org.mockito.Mockito.when(adapter.parseInboundMessages(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(fixture.account()))).thenReturn(java.util.List.of(latest));
        var messages = new ChannelMessageService(new JdbcInboundMessageDeduplicationAdapter(jdbc),
                new JdbcInboundMessagePersistenceAdapter(jdbc, new ObjectMapper(), repository),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        var webhooks = new InboundWebhookService(bindings, messages);

        transactions.executeWithoutResult(tx -> webhooks.accept(Channel.WHATSAPP, "sha256=valid", "webhook-body",
                adapter, "app-secret"));

        var superseded = repository.list(fixture.tenant(), fixture.conversation()).stream()
                .filter(value -> value.id().equals(approval.id())).findFirst().orElseThrow();
        assertThat(superseded.status()).isEqualTo(MessageApproval.Status.SUPERSEDED);
        assertThat(superseded.draftText()).isNull();
        assertThat(repository.lockConversation(fixture.tenant(), fixture.conversation()).orElseThrow().latestReceiptId())
                .isNotEqualTo(approval.sourceReceiptId());
        assertThat(jdbc.sql("select count(*) from message_approval_hold where tenant_id=:tenant and conversation_id=:conversation")
                .param("tenant", fixture.tenant()).param("conversation", fixture.conversation())
                .query(Integer.class).single()).isZero();
    }

    @Test void approvePersistsOneOutboundMessageAndOutboxIntentAndIdempotentReplay() {
        var fixture = fixture();
        var pending = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var workflow = approvalWorkflow(fixture.tenant());
        var request = new com.ford.service.conversations.api.MessageApprovalActionRequest(
                MessageApproval.Action.APPROVE, pending.version(), pending.draftText());

        var result = transactions.execute(tx -> workflow.service().act(workflow.operator(), fixture.tenant(),
                fixture.conversation(), pending.id(), pending.conversationVersion(), "approval-send-key", request));
        var replay = transactions.execute(tx -> workflow.service().act(workflow.operator(), fixture.tenant(),
                fixture.conversation(), pending.id(), pending.conversationVersion(), "approval-send-key", request));

        assertThat(result).isEqualTo(replay);
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id=:id and direction='OUTBOUND'")
                .param("id", fixture.conversation()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_event where tenant_id=:tenant and aggregate_id=:id and status='PENDING'")
                .param("tenant", fixture.tenant()).param("id", fixture.conversation())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(repository.list(fixture.tenant(), fixture.conversation()).getFirst().status())
                .isEqualTo(MessageApproval.Status.QUEUED);
        assertThat(jdbc.sql("select count(*) from message_approval_hold where conversation_id=:id")
                .param("id", fixture.conversation()).query(Integer.class).single()).isZero();
    }

    @Test void approvalAndOutboxWritesRollbackTogetherWhenOuterApprovalTransactionFails() {
        var fixture = fixture();
        var pending = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var workflow = approvalWorkflow(fixture.tenant());
        var request = new com.ford.service.conversations.api.MessageApprovalActionRequest(
                MessageApproval.Action.APPROVE, pending.version(), pending.draftText());

        try {
            transactions.executeWithoutResult(tx -> {
                workflow.service().act(workflow.operator(), fixture.tenant(), fixture.conversation(), pending.id(),
                        pending.conversationVersion(), "approval-rollback-key", request);
                throw new IllegalStateException("simulate failure after the queued command");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("simulate failure after the queued command");
        }

        var restored = repository.list(fixture.tenant(), fixture.conversation()).getFirst();
        assertThat(restored.status()).isEqualTo(MessageApproval.Status.PENDING);
        assertThat(jdbc.sql("select count(*) from message_approval_hold where approval_id=:id")
                .param("id", pending.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id=:id and direction='OUTBOUND'")
                .param("id", fixture.conversation()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id")
                .param("id", fixture.conversation()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select version from conversation where id=:id")
                .param("id", fixture.conversation()).query(Long.class).single()).isEqualTo(8L);
        assertThat(jdbc.sql("select count(*) from operator_idempotency where tenant_id=:tenant and actor_id=:actor")
                .param("tenant", fixture.tenant()).param("actor", workflow.operator().subject())
                .query(Integer.class).single()).isZero();
    }

    @Test void latestAcceptedInboundInvalidatesPendingApprovalBeforeItsTurnRuns() {
        var fixture = fixture();
        var approval = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var newerReceipt = UUID.randomUUID();
        jdbc.sql("insert into channel_message(id,tenant_id,channel,provider_message_id,participant_id,content,received_at,raw_payload_reference,conversation_id) values (:id,:tenant,'WHATSAPP',:provider,:participant,'nuevo mensaje',:at,'test-payload',:conversation)")
                .param("id", newerReceipt).param("tenant", fixture.tenant())
                .param("provider", UUID.randomUUID().toString()).param("participant", fixture.conversation().toString())
                .param("at", Timestamp.from(NOW.plusSeconds(2))).param("conversation", fixture.conversation()).update();

        var target = transactions.execute(tx -> repository.lockConversation(fixture.tenant(), fixture.conversation())).orElseThrow();
        assertThat(target.latestReceiptId()).isEqualTo(newerReceipt);
        assertThat(target.latestReceiptId()).isNotEqualTo(approval.sourceReceiptId());
    }

    @Test void acceptingNewInboundSupersedesApprovalReleasesHoldAndAdvancesConversationVersion() {
        var fixture = fixture();
        var approval = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var newReceipt = UUID.randomUUID();
        var inbound = new JdbcInboundMessagePersistenceAdapter(jdbc, new ObjectMapper(), repository);
        transactions.executeWithoutResult(tx -> inbound.persist(new InboundMessage(fixture.tenant(), Channel.WHATSAPP,
                fixture.account(), "new-provider-message", fixture.conversation().toString(), "New question",
                java.util.List.of(), NOW.plusSeconds(2), "test-payload", null, fixture.conversation()), newReceipt));

        var current = repository.list(fixture.tenant(), fixture.conversation()).stream()
                .filter(value -> value.id().equals(approval.id())).findFirst().orElseThrow();
        assertThat(current.status()).isEqualTo(MessageApproval.Status.SUPERSEDED);
        assertThat(current.draftText()).isNull();
        assertThat(jdbc.sql("select count(*) from message_approval_hold where conversation_id=:id")
                .param("id", fixture.conversation()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select version from conversation where id=:id").param("id", fixture.conversation())
                .query(Long.class).single()).isEqualTo(9L);
        assertThat(transactions.execute(tx -> repository.lockConversation(fixture.tenant(), fixture.conversation()))
                .orElseThrow().latestReceiptId()).isEqualTo(newReceipt);
    }

    @Test void tenantScopedLockAndCasPreserveRejectedHoldAndReleaseQueuedHold() {
        var fixture = fixture();
        var pending = transactions.execute(tx -> repository.savePending(fixture.approval()));

        var target = transactions.execute(tx -> repository.lockConversation(fixture.tenant(), fixture.conversation())).orElseThrow();
        assertThat(target.latestReceiptId()).isEqualTo(fixture.receipt());
        assertThat(target.version()).isEqualTo(8);
        assertThat(repository.list(UUID.randomUUID(), fixture.conversation())).isEmpty();
        assertThat(jdbc.sql("select count(*) from message_approval_hold where tenant_id=:tenant")
                .param("tenant", fixture.tenant()).query(Integer.class).single()).isEqualTo(1);

        var rejected = pending.complete(pending.prepare(MessageApproval.Action.REJECT, 0, 8, null),
                UUID.randomUUID(), 8, null, NOW.plusSeconds(1));
        transactions.executeWithoutResult(tx -> {
            assertThat(repository.replace(pending, rejected)).isTrue();
            repository.recordAudit(fixture.tenant(), rejected.id(), rejected.reviewedBy(),
                    new MessageApprovalRepository.AuditRecord("REJECT", 1, fixture.receipt(), null), NOW.plusSeconds(1));
        });
        assertThat(jdbc.sql("select count(*) from message_approval_hold where tenant_id=:tenant")
                .param("tenant", fixture.tenant()).query(Integer.class).single()).isEqualTo(1);

        var queued = rejected.complete(rejected.prepare(MessageApproval.Action.TAKEOVER, 1, 8, null),
                UUID.randomUUID(), 9, null, NOW.plusSeconds(2));
        transactions.executeWithoutResult(tx -> assertThat(repository.replace(rejected, queued)).isTrue());
        assertThat(jdbc.sql("select count(*) from message_approval_hold where tenant_id=:tenant")
                .param("tenant", fixture.tenant()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from message_approval_action_audit where approval_id=:id")
                .param("id", fixture.approval().id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void onlyOneConcurrentAdvisorCanConsumeApprovalAndRollbackRestoresIt() throws Exception {
        var fixture = fixture();
        var pending = transactions.execute(tx -> repository.savePending(fixture.approval()));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var wins = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) executor.submit(() -> {
                ready.countDown();
                try { if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                try {
                    transactions.executeWithoutResult(tx -> {
                        repository.lockConversation(fixture.tenant(), fixture.conversation());
                        var locked = repository.findForUpdate(fixture.tenant(), fixture.conversation(), fixture.approval().id()).orElseThrow();
                        var next = locked.complete(locked.prepare(MessageApproval.Action.REJECT, 0, 8, null), UUID.randomUUID(), 8, null, NOW.plusSeconds(1));
                        if (repository.replace(locked, next)) wins.incrementAndGet();
                    });
                } catch (IllegalStateException staleLoser) {
                    // Expected: the lock winner consumes the only pending disposition first.
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(wins).hasValue(1);
        var rollbackFixture = fixture();
        var rollbackPending = transactions.execute(tx -> repository.savePending(rollbackFixture.approval()));
        try {
            transactions.executeWithoutResult(tx -> {
                var current = repository.findForUpdate(rollbackFixture.tenant(), rollbackFixture.conversation(), rollbackPending.id()).orElseThrow();
                var next = current.complete(current.prepare(MessageApproval.Action.REJECT, 0, 8, null), UUID.randomUUID(), 8, null, NOW.plusSeconds(1));
                repository.replace(current, next);
                throw new IllegalStateException("simulate outbound outbox failure");
            });
        } catch (IllegalStateException expected) { }
        var restored = transactions.execute(tx -> repository.findForUpdate(rollbackFixture.tenant(), rollbackFixture.conversation(), rollbackFixture.approval().id())).orElseThrow();
        assertThat(restored.status()).isEqualTo(MessageApproval.Status.PENDING);
    }

    private static Fixture fixture() {
        var tenant = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var receipt = UUID.randomUUID();
        var id = UUID.randomUUID();
        var bindingId = UUID.randomUUID();
        var account = UUID.randomUUID().toString();
        jdbc.sql("insert into conversation(id,tenant_id,channel,provider_conversation_id,provider_account_id,participant_id,state,version,created_at,updated_at) values (:id,:tenant,'WHATSAPP',:provider,:account,:participant,'BOT_ACTIVE',7,:at,:at)")
                .param("id", conversation).param("tenant", tenant).param("provider", conversation.toString())
                .param("account", account).param("participant", conversation.toString()).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("insert into conversation_turn(tenant_id,conversation_id,receipt_id,provider_message_id,state,accepted_at,updated_at,version) values (:tenant,:conversation,:receipt,:provider,'REPLY_COMMITTED',:at,:at,0)")
                .param("tenant", tenant).param("conversation", conversation).param("receipt", receipt).param("provider", receipt.toString()).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("insert into channel_message(id,tenant_id,channel,provider_message_id,participant_id,content,received_at,raw_payload_reference,conversation_id) values (:id,:tenant,'WHATSAPP',:provider,:participant,'original',:at,'test-payload',:conversation)")
                .param("id", receipt).param("tenant", tenant).param("provider", receipt.toString())
                .param("participant", conversation.toString()).param("at", Timestamp.from(NOW))
                .param("conversation", conversation).update();
        jdbc.sql("insert into conversation_message(id,conversation_id,direction,provider_message_id,content,received_at) values (:id,:conversation,'INBOUND',:provider,'original',:at)")
                .param("id", UUID.randomUUID()).param("conversation", conversation).param("provider", receipt.toString())
                .param("at", Timestamp.from(NOW)).update();
        jdbc.sql("insert into channel_binding(id,webhook_key_hash,tenant_id,channel,provider_account_id,secret_reference,enabled,created_at,updated_at) values (:id,:hash,:tenant,'WHATSAPP',:account,'test-secret',true,:at,:at)")
                .param("id", bindingId).param("hash", UUID.randomUUID().toString())
                .param("tenant", tenant).param("account", account).param("at", Timestamp.from(NOW)).update();
        var disposition = new AdvisorResponseDisposition(AdvisorResponseDisposition.Route.REVIEW,
                AdvisorResponseDisposition.Reason.RESPONSE_REVIEW);
        return new Fixture(tenant, conversation, receipt, account, bindingId, MessageApproval.pending(id, tenant, conversation,
                receipt, 7, disposition, "Respuesta exacta", true, NOW));
    }

    private ApprovalWorkflow approvalWorkflow(UUID tenantId) {
        var json = new ObjectMapper().findAndRegisterModules();
        var idempotency = new com.ford.service.conversations.adapter.out.JdbcIdempotencyRepository(jdbc);
        var commands = new com.ford.service.conversations.application.DefaultConversationCommandService(
                new com.ford.service.conversations.adapter.out.JdbcOperatorConversationCommandRepository(jdbc,
                        new com.ford.service.outbox.JdbcOutboxStore(jdbc), json), idempotency, json);
        var validation = new JdbcMessageApprovalSendValidationAdapter(jdbc,
                new JdbcAcceptedInboundTurnRepository(jdbc, json), new JdbcCustomerWindowAdapter(jdbc),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        var effects = new com.ford.service.conversations.application.DefaultMessageApprovalEffects(commands,
                validation, org.mockito.Mockito.mock(com.ford.service.conversations.api.MessageApprovalTakeoverPort.class),
                repository);
        var service = new com.ford.service.conversations.application.MessageApprovalService(repository, effects,
                idempotency, json, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        var actorId = UUID.randomUUID();
        var operator = new com.ford.service.operator.application.AuthenticatedOperator(actorId.toString(),
                java.util.Set.of("SCOPE_operator:write", "SCOPE_handoff:manage"), java.util.Set.of(),
                java.util.Set.of(tenantId));
        return new ApprovalWorkflow(service, operator);
    }

    private record ApprovalWorkflow(com.ford.service.conversations.application.MessageApprovalService service,
                                    com.ford.service.operator.application.AuthenticatedOperator operator) { }
    private record Fixture(UUID tenant, UUID conversation, UUID receipt, String account, UUID bindingId,
                           MessageApproval approval) { }
}
