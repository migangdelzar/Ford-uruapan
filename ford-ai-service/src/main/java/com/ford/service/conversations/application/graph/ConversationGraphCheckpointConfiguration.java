package com.ford.service.conversations.application.graph;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ConversationGraphCheckpointConfiguration {

    private static final String CHECKPOINT_SCHEMA = "ford_graph";

    @Bean(name = "conversationGraphCheckpointDataSource", destroyMethod = "close")
    HikariDataSource conversationGraphCheckpointDataSource(DataSourceProperties properties) {
        var config = new HikariConfig();
        config.setJdbcUrl(properties.determineUrl());
        config.setUsername(properties.determineUsername());
        config.setPassword(properties.determinePassword());
        config.setPoolName("ford-graph-checkpoints");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.addDataSourceProperty("currentSchema", CHECKPOINT_SCHEMA);
        return new HikariDataSource(config);
    }

    @Bean
    @DependsOnDatabaseInitialization
    FordPostgresSaverV2 conversationGraphCheckpointSaver(
            @Qualifier("conversationGraphCheckpointDataSource") HikariDataSource dataSource) throws Exception {
        return FordPostgresSaverV2.create(dataSource);
    }
}
