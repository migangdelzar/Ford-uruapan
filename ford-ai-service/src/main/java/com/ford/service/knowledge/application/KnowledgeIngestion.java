package com.ford.service.knowledge.application;

import org.springframework.ai.document.Document;
import org.springframework.modulith.NamedInterface;

import java.util.List;

@NamedInterface
public interface KnowledgeIngestion {

    void ingest(List<Document> documents);
}
