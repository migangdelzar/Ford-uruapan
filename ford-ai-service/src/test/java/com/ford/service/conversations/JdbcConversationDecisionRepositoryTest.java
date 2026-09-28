package com.ford.service.conversations;

import com.ford.service.ai.application.ClassificationEvidence;
import com.ford.service.ai.application.ClassifierType;
import com.ford.service.ai.application.ConversationDecision;
import com.ford.service.ai.application.ConversationIntent;
import com.ford.service.ai.application.HandoffRecommendation;
import com.ford.service.ai.application.LeadStage;
import com.ford.service.ai.application.LeadTemperature;
import com.ford.service.ai.application.SensitiveDataLevel;
import com.ford.service.ai.application.ToolFamily;
import com.ford.service.conversations.adapter.out.JdbcConversationDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConversationDecisionRepositoryTest {

    private JdbcClient jdbc;
    private JdbcConversationDecisionRepository repository;
    private UUID conversationId;

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:decision;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop table if exists ai_execution").update();
        jdbc.sql("drop table if exists conversation_decision").update();
        jdbc.sql("drop table if exists conversation").update();
        jdbc.sql("create table conversation (id uuid primary key, tenant_id uuid not null)").update();
        jdbc.sql("""
                create table conversation_decision (
                    id uuid primary key,
                    conversation_id uuid not null references conversation(id),
                    provider_message_id varchar(240) not null,
                    classifier_type varchar(60) not null,
                    classification varchar(120) not null,
                    confidence numeric(8,6) not null,
                    reason_code varchar(120) not null,
                    provider varchar(80) not null,
                    model varchar(160) not null,
                    latency_ms bigint not null,
                    fallback_used boolean not null,
                    next_action varchar(40) not null default 'NO_ACTION',
                    urgency_signal boolean not null default false,
                    summary varchar(280) not null default '',
                    created_at timestamp with time zone not null,
                    unique (conversation_id, provider_message_id, classifier_type)
                )
                """).update();
        jdbc.sql("""
                create table ai_execution (
                    id uuid primary key,
                    tenant_id uuid not null,
                    conversation_id uuid not null references conversation(id),
                    provider_message_id varchar(240) not null,
                    classifier_type varchar(60) not null,
                    provider varchar(80) not null,
                    model varchar(160) not null,
                    latency_ms bigint not null,
                    classification_confidence numeric(8,6),
                    outcome varchar(80) not null,
                    fallback_used boolean not null,
                    handoff_recommended boolean not null,
                    created_at timestamp with time zone not null,
                    unique (conversation_id, provider_message_id, classifier_type)
                )
                """).update();
        conversationId = UUID.randomUUID();
        jdbc.sql("insert into conversation (id, tenant_id) values (:id, :tenantId)")
                .param("id", conversationId).param("tenantId", UUID.randomUUID()).update();
        repository = new JdbcConversationDecisionRepository(jdbc);
    }

    @Test
    void savesOneNormalizedEvidenceRowPerClassifierAndIsIdempotent() {
        var decision = decision();

        repository.save(conversationId, "wamid.1", decision);
        repository.save(conversationId, "wamid.1", decision);

        assertThat(jdbc.sql("select count(*) from conversation_decision")
                .query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select count(*) from conversation_decision where provider = 'typesafe'")
                .query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select distinct next_action from conversation_decision")
                .query(String.class).list()).containsExactly("NO_ACTION");
    }

    private static ConversationDecision decision() {
        return new ConversationDecision(ConversationIntent.FINANCE, LeadTemperature.WARM,
                LeadStage.QUALIFYING, HandoffRecommendation.NONE, ToolFamily.FINANCE_ESTIMATE,
                SensitiveDataLevel.NONE, List.of(
                        evidence(ClassifierType.INTENT, "FINANCE"),
                        evidence(ClassifierType.LEAD, "QUALIFYING/WARM"),
                        evidence(ClassifierType.HANDOFF, "NONE"),
                        evidence(ClassifierType.TOOL_FAMILY, "FINANCE_ESTIMATE"),
                        evidence(ClassifierType.SENSITIVE_DATA, "NONE")));
    }

    private static ClassificationEvidence evidence(ClassifierType type, String value) {
        return new ClassificationEvidence(type, value, 0.91, "model_choice",
                "typesafe", "jev", 20, false);
    }
}
