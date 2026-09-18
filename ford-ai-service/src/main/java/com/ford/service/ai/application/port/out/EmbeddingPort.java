package com.ford.service.ai.application.port.out;

public interface EmbeddingPort {
    float[] embed(String content);

    String provider();

    String modelVersion();
}
