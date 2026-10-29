package com.ford.service.channels.domain;

/** A selectable identity label. It carries no vehicle facts or availability. */
public record ReplyOption(String id, String title) {
    public ReplyOption {
        if (id == null || !id.matches("vehicle-[a-f0-9]{16}")) {
            throw new IllegalArgumentException("reply option ID is invalid");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("reply option title is required");
        }
    }
}
