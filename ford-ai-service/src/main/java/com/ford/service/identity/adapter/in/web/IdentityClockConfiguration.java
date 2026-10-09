package com.ford.service.identity.adapter.in.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

@Configuration
public class IdentityClockConfiguration {

    @Bean
    @Primary
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
