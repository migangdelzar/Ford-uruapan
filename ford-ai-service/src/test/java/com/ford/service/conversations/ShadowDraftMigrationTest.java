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

class ShadowDraftMigrationTest {

    @Test
    void appliesShadowDraftChangeSet() throws Exception {
        Class.forName("org.h2.Driver");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:shadow-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            var liquibase = new Liquibase("db/changelog/changes/027-conversation-shadow-draft.yaml",
                    new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(), new LabelExpression());
        }

        var jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("select count(*) from information_schema.columns "
                        + "where table_name = 'CONVERSATION_SHADOW_DRAFT' and column_name = 'REVIEW_STATUS'")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
