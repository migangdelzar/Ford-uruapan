package com.ford.service.health.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public HealthStatus get() {
        return new HealthStatus("ok", Instant.now());
    }

    public record HealthStatus(String status, Instant timestamp) {
    }
}
