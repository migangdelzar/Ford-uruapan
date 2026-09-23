package com.ford.service.channels.application.port.in;

import java.util.List;

public record TemplateMessageCommand(String templateName, String language, List<String> parameters) {
    public TemplateMessageCommand {
        if (templateName == null || !templateName.matches("[a-z0-9_]{1,120}")) {
            throw new IllegalArgumentException("templateName must contain lowercase letters, digits, and underscores");
        }
        if (language == null || !language.matches("[a-z]{2}_[A-Z]{2}")) {
            throw new IllegalArgumentException("language must use the format es_MX");
        }
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        if (parameters.size() > 10) throw new IllegalArgumentException("template parameters must not exceed 10");
        if (parameters.stream().anyMatch(value -> value == null || value.length() > 1024)) {
            throw new IllegalArgumentException("template parameter is invalid");
        }
    }
}
