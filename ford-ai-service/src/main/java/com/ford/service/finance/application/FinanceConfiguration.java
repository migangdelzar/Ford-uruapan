package com.ford.service.finance.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FinanceConfiguration {

    @Bean
    DeterministicFinanceCalculator deterministicFinanceCalculator() {
        return new DeterministicFinanceCalculator();
    }

    @Bean
    FinanceOperations financeOperations(DeterministicFinanceCalculator calculator) {
        return new FinanceService(calculator);
    }
}
