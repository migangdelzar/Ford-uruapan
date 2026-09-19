package com.ford.service.knowledge.application.port.out;

import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;

import java.util.List;

public interface KnowledgeRetriever {
    List<KnowledgeChunk> retrieve(KnowledgeQuery query);
}
