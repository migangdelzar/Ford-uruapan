package com.ford.service.ai.adapter.in.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemPromptUpdateRequest(
        @NotBlank @Size(max = 32_000) String content,
        @Min(0) long expectedVersion) {
}
