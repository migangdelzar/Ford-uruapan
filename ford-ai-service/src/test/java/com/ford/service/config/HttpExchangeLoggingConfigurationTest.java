package com.ford.service.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpExchangeLoggingConfigurationTest {

    @Test
    void registersAConcreteFilterInstanceForTheServletContainer() {
        var registration = new HttpExchangeLoggingConfiguration().httpExchangeLoggingFilter();

        assertThat(registration.getFilter())
                .isExactlyInstanceOf(HttpExchangeLoggingFilter.class);
    }
}
