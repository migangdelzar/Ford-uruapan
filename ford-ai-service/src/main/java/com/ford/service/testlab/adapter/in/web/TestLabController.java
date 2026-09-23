package com.ford.service.testlab.adapter.in.web;

import com.ford.service.testlab.application.TestLabProperties;
import com.ford.service.testlab.application.TestLabService;
import com.ford.service.testlab.application.TestLabService.FaultSettings;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test-lab/faults")
@PreAuthorize("hasAuthority('SCOPE_testlab:run') || hasRole('ADMIN')")
public class TestLabController {
    private final TestLabProperties properties;
    private final TestLabService service;

    public TestLabController(TestLabProperties properties, TestLabService service) {
        this.properties = properties;
        this.service = service;
    }

    @GetMapping
    public FaultSettings get() {
        requireEnabled();
        return service.get();
    }

    @PutMapping
    public FaultSettings replace(@RequestBody FaultSettings settings) {
        requireEnabled();
        return service.replace(settings);
    }

    private void requireEnabled() {
        if (!properties.enabled()) throw new IllegalStateException("test lab is disabled");
    }
}
