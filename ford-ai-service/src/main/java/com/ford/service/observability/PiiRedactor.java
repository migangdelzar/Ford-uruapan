package com.ford.service.observability;

public interface PiiRedactor {
    RedactedPayload redact(String payload);
}
