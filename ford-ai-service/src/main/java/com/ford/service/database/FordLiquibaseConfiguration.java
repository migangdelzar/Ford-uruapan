package com.ford.service.database;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class FordLiquibaseConfiguration {

    @Bean(name = "fordMetadataLiquibaseBootstrap")
    InitializingBean fordMetadataLiquibaseBootstrap(DataSource dataSource) {
        var bootstrap = new FordMetadataLiquibaseBootstrap(dataSource);
        return bootstrap::initialize;
    }

    @Bean
    static BeanFactoryPostProcessor runMetadataBootstrapBeforeLiquibase() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("liquibase")) {
                return;
            }
            var liquibase = beanFactory.getBeanDefinition("liquibase");
            var currentDependencies = liquibase.getDependsOn();
            if (currentDependencies == null) {
                liquibase.setDependsOn("fordMetadataLiquibaseBootstrap");
                return;
            }
            var dependencies = java.util.Arrays.copyOf(currentDependencies, currentDependencies.length + 1);
            dependencies[currentDependencies.length] = "fordMetadataLiquibaseBootstrap";
            liquibase.setDependsOn(dependencies);
        };
    }
}
