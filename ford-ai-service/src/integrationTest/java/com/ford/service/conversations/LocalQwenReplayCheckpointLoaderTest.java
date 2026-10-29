package com.ford.service.conversations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ford.service.channels.domain.Channel;
import com.ford.service.database.FordPostgresContainer;
import com.ford.service.conversations.adapter.out.JdbcConversationMemoryRepository;
import com.ford.service.conversations.application.MemoryProperties;
import com.ford.service.conversations.domain.ConversationMemoryKey;
import com.ford.service.conversations.domain.MemoryQuery;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LocalQwenReplayCheckpointLoaderTest {
    @TempDir Path tempDir;
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new FordPostgresContainer();

    static JdbcClient jdbc;

    @BeforeAll
    static void migrateSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void restoresSyntheticMessagesMemoryAndConversationVersion() throws Exception {
        var tenant = UUID.randomUUID();
        var checkpoint = checkpoint();
        var loader = new LocalQwenReplayCheckpointLoader(jdbc, new ObjectMapper());

        var scenario = scenario();
        var conversationId = loader.restore(checkpoint, tenant, "WHATSAPP", scenario,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "a".repeat(64), 1,
                java.time.Duration.ofDays(90));

        assertThat(jdbc.sql("select version from conversation where id=:id").param("id", conversationId)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("select count(*) from conversation_message where conversation_id=:id")
                .param("id", conversationId).query(Integer.class).single()).isEqualTo(2);
        var key = new ConversationMemoryKey(tenant, Channel.WHATSAPP, checkpoint.providerAccountId(),
                checkpoint.participantId(), conversationId);
        var properties = new MemoryProperties();
        var memory = new JdbcConversationMemoryRepository(jdbc, new ObjectMapper(), properties)
                .load(new MemoryQuery(key, "próximo turno", 10, 0));
        assertThat(memory.summary()).isEqualTo("Pickup familiar sintética.");
        assertThat(memory.durableFacts()).extracting("kind").containsExactly("use_case");
        assertThat(memory.recentMessages()).extracting("content")
                .containsExactly("Hola.", "Hola, ¿en qué ayudo?");

        var exportedPath = new LocalQwenReplayCheckpointExporter(jdbc, new ObjectMapper()).export(tempDir,
                "loader-export-turn-001", tenant, conversationId, 1, scenario,
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "a".repeat(64), "SEEDED_REPLAY_EXPORT");
        var exported = LocalQwenReplayCheckpoint.read(exportedPath, new ObjectMapper());
        exported.validate(scenario, LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "a".repeat(64), 1);
        assertThat(exported.artifactOrigin()).isEqualTo("SEEDED_REPLAY_EXPORT");
    }

    @Test
    void doesNotExportSeededCompactionSkipMarkerAsConversationMemory() throws Exception {
        var tenant = UUID.randomUUID();
        var fixture = checkpoint();
        var identity = UUID.randomUUID().toString().replace("-", "");
        var checkpoint = new LocalQwenReplayCheckpoint(fixture.schemaVersion(), "loader-skip-marker-" + identity,
                fixture.scenarioId(), fixture.scenarioSha256(), fixture.snapshotLabel(), fixture.snapshotSha256(),
                fixture.checkpointTurn(), "synthetic-account-skip-" + identity,
                "synthetic-participant-skip-" + identity, "synthetic-participant-skip-" + identity,
                fixture.conversationVersion(), fixture.memoryVersion(),
                "Compaction skipped in seeded diagnostic.", fixture.memoryUpdatedAt(), fixture.contextStartedAt(),
                fixture.messages(), fixture.durableFacts(), fixture.artifactOrigin());
        var conversationId = new LocalQwenReplayCheckpointLoader(jdbc, new ObjectMapper()).restore(checkpoint,
                tenant, "WHATSAPP", scenario(), LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE,
                "a".repeat(64), 1, java.time.Duration.ofDays(90));

        var exportedPath = new LocalQwenReplayCheckpointExporter(jdbc, new ObjectMapper()).export(tempDir,
                "loader-export-seeded-skip-marker", tenant, conversationId, 1, scenario(),
                LocalCatalogSnapshotSelection.Label.OCT25_CANDIDATE, "a".repeat(64), "SEEDED_REPLAY_EXPORT");

        assertThat(LocalQwenReplayCheckpoint.read(exportedPath, new ObjectMapper()).memorySummary()).isEmpty();
    }

    private static LocalQwenReplayCheckpoint checkpoint() {
        var scenario = scenario();
        var now = Instant.now();
        var assistantAt = now.plusMillis(1);
        return new LocalQwenReplayCheckpoint(1, "loader-test-checkpoint", "natural-buyer-100",
                LocalQwenReplayCheckpoint.scenarioSha256(scenario), "oct25-candidate", "a".repeat(64), 1,
                "synthetic-account-loader-test", "synthetic-participant-loader-test",
                "synthetic-participant-loader-test", 1, 1, "Pickup familiar sintética.", now.toString(),
                now.toString(), List.of(new LocalQwenReplayCheckpoint.Message(1, "USER", "Hola.", now.toString()),
                        new LocalQwenReplayCheckpoint.Message(1, "ASSISTANT", "Hola, ¿en qué ayudo?", assistantAt.toString())),
                List.of(new LocalQwenReplayCheckpoint.Fact("use_case", "pickup familiar")), "SYNTHETIC_FIXTURE");
    }

    private static List<NaturalBuyerConversationScenario.Turn> scenario() {
        return List.of(new NaturalBuyerConversationScenario.Turn(1, "Hola.",
                NaturalBuyerConversationScenario.Expectation.SOCIAL),
                new NaturalBuyerConversationScenario.Turn(2, "Busco pickup.",
                        NaturalBuyerConversationScenario.Expectation.DISCOVERY));
    }
}
