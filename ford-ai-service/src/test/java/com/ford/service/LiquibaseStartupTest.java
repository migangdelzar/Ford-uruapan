package com.ford.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.liquibase.enabled=true",
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-test.yaml"
})
@ActiveProfiles("test")
class LiquibaseStartupTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void runsLiquibaseAtApplicationStartup() {
        assertThat(jdbc.sql("select count(*) from liquibase_startup_probe")
                .query(Integer.class)
                .single())
                .isEqualTo(1);
    }
}
