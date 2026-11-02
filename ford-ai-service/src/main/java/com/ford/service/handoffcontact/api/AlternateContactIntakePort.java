package com.ford.service.handoffcontact.api;

import java.time.Instant;
import java.util.List;

/** Conversation/channel boundary for contact-safe input and one-turn encrypted staging. */
public interface AlternateContactIntakePort {
    String redact(String text);

    void captureAndStage(StagingContext context, String rawText, Instant receivedAt);

    List<AlternateContactValue> find(StagingContext context, Instant now);

    void delete(StagingContext context);
}
