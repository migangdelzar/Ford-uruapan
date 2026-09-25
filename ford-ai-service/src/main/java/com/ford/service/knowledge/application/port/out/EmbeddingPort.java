package com.ford.service.knowledge.application.port.out;

public interface EmbeddingPort {
    float[] embed(String content);

    String provider();

    String modelVersion();
}
