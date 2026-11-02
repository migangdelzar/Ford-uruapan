package com.ford.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication(excludeName = "org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration")
@Modulithic(systemName = "ford-service")
public class FordServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FordServiceApplication.class, args);
    }
}
