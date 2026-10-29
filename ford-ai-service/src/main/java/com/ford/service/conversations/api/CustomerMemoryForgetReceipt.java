package com.ford.service.conversations.api;

import java.time.Instant;
import java.util.UUID;

public record CustomerMemoryForgetReceipt(UUID conversationId, int removedFactCount, Instant forgottenAt) { }
