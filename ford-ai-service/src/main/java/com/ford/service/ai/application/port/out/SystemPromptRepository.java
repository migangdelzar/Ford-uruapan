package com.ford.service.ai.application.port.out;

import com.ford.service.ai.application.SystemPromptSnapshot;

import java.util.Optional;

public interface SystemPromptRepository {
    Optional<SystemPromptSnapshot> find();

    Optional<SystemPromptSnapshot> replace(String content, long expectedVersion, String updatedBy);
}
