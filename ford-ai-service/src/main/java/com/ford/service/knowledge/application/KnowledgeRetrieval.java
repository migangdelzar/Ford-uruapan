package com.ford.service.knowledge.application;

import com.ford.service.knowledge.domain.KnowledgeChunk;
import com.ford.service.knowledge.domain.KnowledgeQuery;
import org.springframework.modulith.NamedInterface;

import java.util.List;

@NamedInterface
public interface KnowledgeRetrieval {

    List<KnowledgeChunk> retrieve(KnowledgeQuery query);
}
