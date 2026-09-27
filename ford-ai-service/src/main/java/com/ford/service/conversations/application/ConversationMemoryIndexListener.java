package com.ford.service.conversations.application;

import com.ford.service.conversations.api.ConversationMemoryIndexRequested;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public final class ConversationMemoryIndexListener {

    private static final Pattern EMAIL = Pattern.compile("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{7,}\\d");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._-]+");
    private static final int MAX_CONTENT_CHARS = 4_000;

    private final ConversationMemoryRecallPort recall;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final MemoryProperties properties;

    public ConversationMemoryIndexListener(ConversationMemoryRecallPort recall,
                                            ObjectProvider<EmbeddingModel> embeddingModels,
                                            MemoryProperties properties) {
        this.recall = recall;
        this.embeddingModels = embeddingModels;
        this.properties = properties;
    }

    @ApplicationModuleListener
    public void on(ConversationMemoryIndexRequested event) {
        if (!properties.semanticRecallEnabled()
                || event == null
                || event.message() == null
                || event.message().content() == null
                || event.message().content().isBlank()) {
            return;
        }
        var embeddingModel = embeddingModels.getIfAvailable();
        if (embeddingModel == null) {
            return;
        }
        var redactedContent = redact(event.message().content());
        recall.upsert(event.key(), event.message(), redactedContent,
                properties.semanticEmbeddingModel(), embeddingModel.embed(redactedContent));
    }

    static String redact(String content) {
        var bounded = content.length() > MAX_CONTENT_CHARS
                ? content.substring(0, MAX_CONTENT_CHARS)
                : content;
        var withoutContactData = PHONE.matcher(EMAIL.matcher(bounded)
                .replaceAll("[EMAIL]")).replaceAll("[PHONE]");
        return BEARER.matcher(withoutContactData).replaceAll("[REDACTED]").trim();
    }
}
