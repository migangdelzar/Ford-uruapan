package com.ford.service.ai.adapter.in.web;

import com.ford.service.ai.application.SystemPromptService;
import com.ford.service.ai.application.SystemPromptSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/operator/ai/system-prompt")
public class SystemPromptController {

    private final SystemPromptService service;

    public SystemPromptController(SystemPromptService service) {
        this.service = service;
    }

    @GetMapping
    public SystemPromptResponse current() {
        return response(service.current());
    }

    @PutMapping
    public SystemPromptResponse replace(@Valid @RequestBody SystemPromptUpdateRequest request,
                                        Principal principal) {
        var actor = principal == null ? "operator" : principal.getName();
        return response(service.replace(request.content(), request.expectedVersion(), actor));
    }

    @ExceptionHandler(SystemPromptService.VersionConflictException.class)
    public ResponseEntity<Void> handleVersionConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleInvalidRequest() {
        return ResponseEntity.badRequest().build();
    }

    private static SystemPromptResponse response(SystemPromptSnapshot snapshot) {
        return new SystemPromptResponse(snapshot.content(), snapshot.version(), snapshot.updatedBy(), snapshot.updatedAt());
    }
}
