package com.ford.service.ai.application;

import com.ford.service.ai.application.port.out.SystemPromptRepository;
import com.ford.service.ai.domain.SystemPromptChanged;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

@Service
public class SystemPromptService {

    public static final int MAX_CONTENT_LENGTH = 32_000;

    private final SystemPromptRepository repository;
    private final String seedContent;
    private final ApplicationEventPublisher events;

    @Autowired
    public SystemPromptService(SystemPromptRepository repository,
                               ResourceLoader resourceLoader,
                               @Value("${ford.ai.system-prompt.location:classpath:/prompts/ford-uruapan-system.md}") String seedLocation,
                               ApplicationEventPublisher events) {
        this(repository, readSeed(resourceLoader, seedLocation), events);
    }

    public SystemPromptService(SystemPromptRepository repository,
                               String seedContent,
                               ApplicationEventPublisher events) {
        this.repository = Objects.requireNonNull(repository);
        this.seedContent = validateContent(seedContent);
        this.events = Objects.requireNonNull(events);
    }

    public SystemPromptSnapshot current() {
        return repository.find().orElseGet(() ->
                new SystemPromptSnapshot(seedContent, 0, "classpath-seed", Instant.EPOCH));
    }

    public String currentContent() {
        return current().content();
    }

    @Transactional
    public SystemPromptSnapshot replace(String content, long expectedVersion, String updatedBy) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        var normalizedContent = validateContent(content);
        var actor = updatedBy == null || updatedBy.isBlank() ? "operator" : updatedBy;
        var updated = repository.replace(normalizedContent, expectedVersion, actor)
                .orElseThrow(() -> new VersionConflictException(expectedVersion));
        events.publishEvent(new SystemPromptChanged(updated.version(), updated.updatedBy(), updated.updatedAt()));
        return updated;
    }

    private static String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("system prompt content must not be blank");
        }
        var normalized = content.strip();
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("system prompt content exceeds 32000 characters");
        }
        return normalized;
    }

    private static String readSeed(ResourceLoader resourceLoader, String location) {
        try (var input = resourceLoader.getResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load system prompt seed: " + location, exception);
        }
    }

    public static final class VersionConflictException extends RuntimeException {
        public VersionConflictException(long expectedVersion) {
            super("system prompt version conflict for expected version " + expectedVersion);
        }
    }
}
