package com.ford.service.observability;

import java.util.Map;
import java.util.function.Supplier;

public interface AiObservationService {

    <T> T observe(String operation, Map<String, String> lowCardinalityTags, Supplier<T> action);

    void event(String operation, Map<String, String> lowCardinalityTags);
}
