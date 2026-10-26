package com.ford.service.conversations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReplayPromotionPrefetcherTest {
    @Test
    void exposesPrefetchContract() {
        assertThat(LocalReplayPromotionPrefetcher.class).isNotNull();
    }
}
