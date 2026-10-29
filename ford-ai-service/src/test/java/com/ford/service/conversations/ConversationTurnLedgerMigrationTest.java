package com.ford.service.conversations;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTurnLedgerMigrationTest {

    @Test
    void createsTenantScopedTurnLedgerAndDurableReviewTables() throws Exception {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-turn-ledger-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            var liquibase = new Liquibase("db/changelog/changes/030-conversation-turn-ledger.yaml",
                    new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(), new LabelExpression());
        }

        var jdbc = JdbcClient.create(dataSource);
        assertThat(tableExists(jdbc, "CONVERSATION_TURN")).isTrue();
        assertThat(tableExists(jdbc, "CONVERSATION_REVIEW_ITEM")).isTrue();
        assertThat(jdbc.sql("select count(*) from information_schema.columns "
                        + "where table_name = 'CONVERSATION_TURN' and column_name = 'RECEIPT_ID'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    private static boolean tableExists(JdbcClient jdbc, String tableName) {
        return jdbc.sql("select count(*) from information_schema.tables where table_name = :tableName")
                .param("tableName", tableName)
                .query(Integer.class).single() == 1;
    }
}
