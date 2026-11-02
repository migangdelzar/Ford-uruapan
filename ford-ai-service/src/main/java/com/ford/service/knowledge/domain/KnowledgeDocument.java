package com.ford.service.knowledge.domain;

import java.util.UUID;

public record KnowledgeDocument(UUID id, String title, String content, String documentType, String locale) {
}
