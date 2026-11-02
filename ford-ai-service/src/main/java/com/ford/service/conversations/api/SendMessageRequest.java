package com.ford.service.conversations.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank @Size(max = 4096) String content,
        @Size(max = 120) String clientMessageId,
        boolean internalNote) {
}
