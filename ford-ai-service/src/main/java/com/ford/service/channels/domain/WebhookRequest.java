package com.ford.service.channels.domain;

import java.util.Map;

public record WebhookRequest(String body, Map<String, String> headers) {
}
