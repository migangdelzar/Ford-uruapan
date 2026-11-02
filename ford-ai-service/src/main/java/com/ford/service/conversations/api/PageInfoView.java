package com.ford.service.conversations.api;

public record PageInfoView(int pageSize, String nextCursor, boolean hasNext) {
}
