package com.ford.service.ai.application;

import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, validated read-only tool names authorized for one conversation turn. */
public record AuthorizedToolSet(Set<String> names) {
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,79}");

    public AuthorizedToolSet {
        names = Set.copyOf(names == null ? Set.of() : names);
        if (names.stream().anyMatch(name -> name == null || !TOOL_NAME.matcher(name).matches())) {
            throw new IllegalArgumentException("authorized tool name is invalid");
        }
    }
}
