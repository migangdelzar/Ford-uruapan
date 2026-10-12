package com.ford.service.conversations;

import com.ford.service.conversations.application.ConversationRuntimeSettings;
import com.ford.service.conversations.application.ConversationRuntimeSettingsCache;
import com.ford.service.conversations.application.ConversationRuntimeSettingsService;
import com.ford.service.conversations.application.ConversationRuntimeSettingsSnapshot;
import com.ford.service.conversations.application.port.out.ConversationRuntimeSettingsRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationRuntimeSettingsServiceTest {

    @Test
    void servesCachedSettingsWithoutReadingTheRepository() {
        var repository = mock(ConversationRuntimeSettingsRepository.class);
        var cache = mock(ConversationRuntimeSettingsCache.class);
        var tenantId = UUID.randomUUID();
        var snapshot = snapshot(tenantId, 7, true, 12);
        when(cache.load(tenantId)).thenReturn(Optional.of(snapshot));
        var service = new ConversationRuntimeSettingsService(repository, cache,
                new ConversationRuntimeSettings(false, true, 20));

        assertThat(service.current(tenantId)).isEqualTo(snapshot);
        verify(repository, never()).find(tenantId);
    }

    @Test
    void fallsBackToPostgresWhenRedisFailsAndReturnsSafeDefaultsForMissingTenantRow() {
        var repository = mock(ConversationRuntimeSettingsRepository.class);
        var cache = mock(ConversationRuntimeSettingsCache.class);
        var tenantId = UUID.randomUUID();
        when(cache.load(tenantId)).thenThrow(new IllegalStateException("redis unavailable"));
        when(repository.find(tenantId)).thenReturn(Optional.empty());
        var defaults = new ConversationRuntimeSettings(false, true, 20);
        var service = new ConversationRuntimeSettingsService(repository, cache, defaults);

        assertThat(service.current(tenantId).settings()).isEqualTo(defaults);
        verify(cache).store(any(ConversationRuntimeSettingsSnapshot.class), any());
    }

    @Test
    void fallsBackToDeploymentDefaultsWhenBothRedisAndPostgresAreUnavailable() {
        var repository = mock(ConversationRuntimeSettingsRepository.class);
        var cache = mock(ConversationRuntimeSettingsCache.class);
        var tenantId = UUID.randomUUID();
        when(cache.load(tenantId)).thenThrow(new IllegalStateException("redis unavailable"));
        when(repository.find(tenantId)).thenThrow(new IllegalStateException("database unavailable"));
        var defaults = new ConversationRuntimeSettings(false, true, 20);
        var service = new ConversationRuntimeSettingsService(repository, cache, defaults);

        assertThat(service.current(tenantId).settings()).isEqualTo(defaults);
    }

    @Test
    void persistsWithRevisionCheckThenRefreshesSharedCache() {
        var repository = mock(ConversationRuntimeSettingsRepository.class);
        var cache = mock(ConversationRuntimeSettingsCache.class);
        var tenantId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var requested = new ConversationRuntimeSettings(true, false, 8);
        when(repository.update(tenantId, actorId, 3, requested)).thenReturn(snapshot(tenantId, 4, true, 8));
        var service = new ConversationRuntimeSettingsService(repository, cache,
                new ConversationRuntimeSettings(false, true, 20));

        var updated = service.update(tenantId, actorId, 3, requested);

        assertThat(updated.revision()).isEqualTo(4);
        verify(cache).store(eq(updated), any());
    }

    @Test
    void rejectsUnsafeLimitsAtTheSettingsBoundary() {
        assertThatThrownBy(() -> new ConversationRuntimeSettings(true, true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptRecentMessageLimit");
    }

    private static ConversationRuntimeSettingsSnapshot snapshot(UUID tenantId, long revision,
                                                                 boolean handoffEnabled, int messages) {
        return new ConversationRuntimeSettingsSnapshot(tenantId, revision,
                new ConversationRuntimeSettings(handoffEnabled, true, messages),
                Instant.parse("2026-10-12T10:00:00Z"), UUID.randomUUID());
    }
}
