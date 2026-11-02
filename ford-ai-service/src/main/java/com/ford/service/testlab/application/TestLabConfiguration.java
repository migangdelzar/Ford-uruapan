package com.ford.service.testlab.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TestLabProperties.class)
public class TestLabConfiguration {
}
