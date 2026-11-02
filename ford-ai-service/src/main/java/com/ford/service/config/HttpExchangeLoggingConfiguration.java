package com.ford.service.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class HttpExchangeLoggingConfiguration {

    @Bean
    FilterRegistrationBean<HttpExchangeLoggingFilter> httpExchangeLoggingFilter() {
        var registration = new FilterRegistrationBean<>(new HttpExchangeLoggingFilter());
        registration.setName("httpExchangeLoggingFilter");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
