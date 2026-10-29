package com.ford.service.shared;

public enum SourceKind {
    MANUAL_OVERRIDE(0),
    OFFICIAL_FEED(1),
    AUTHORIZED_SCRAPER(2),
    SEED(3);

    private final int precedence;

    SourceKind(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return precedence;
    }
}
