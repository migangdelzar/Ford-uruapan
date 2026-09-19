package com.ford.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic(systemName = "ford-service")
public class FordServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FordServiceApplication.class, args);
    }
}
