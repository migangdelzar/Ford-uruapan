# Redis Hot Conversation Memory Design

## Status

Approved direction; implementation pending review of this specification.

## Goal

Use Redis as the hot-memory cache for active conversations while keeping PostgreSQL as the durable source of truth. The design must reduce latency for active sessions, survive Redis eviction or restart, preserve tenant/channel isolation, and remain compatible with the synchronous Spring MVC and virtual-thread runtime.

## Current state

The published `main` branch already persists conversation messages and a durable memory projection in PostgreSQL. The projection contains a bounded summary, durable facts, and an optimistic `memory_version`. Optional semantic memory is stored in PostgreSQL with pgvector. Redis is provisioned and configured through Spring Data Redis, but `ford.memory.cache-enabled` is currently only a property; no cache adapter uses it yet.

Spring AI receives the loaded memory as prompt context. Redis will not become a second conversation owner, and MCP will not own conversation memory.

## Architecture

```text
ConversationWorkflow
        |
        v
ConversationMemoryService
        |
        v
RedisCachedConversationMemoryPort
        | cache hit
        +----------------------> Redis
        |
        | miss, expired, stale, or unavailable
        v
JdbcConversationMemoryRepository ----> PostgreSQL
```

`RedisCachedConversationMemoryPort` decorates the existing `ConversationMemoryPort`; the application service and domain model remain unaware of Redis. The decorator is enabled only when `ford.memory.cache-enabled=true`. When disabled, the existing JDBC path remains the complete implementation.

## Read path

1. Build the full conversation identity from tenant, channel, provider account, participant, and conversation ID.
2. Read the versioned memory entry from Redis.
3. Accept the cache only when the entry is structurally valid and its `memoryVersion` is not older than the durable projection version known by the cache contract.
4. On a miss or stale entry, load the bounded context from PostgreSQL and repopulate Redis with a TTL.
5. If Redis is unavailable or serialization fails, log the cache failure at `WARN` with identifiers and exception type only, then continue with PostgreSQL.

The cache is therefore fail-open: Redis improves latency but never prevents a conversation from working.

## Write and consistency model

PostgreSQL remains authoritative.

- Inbound and outbound messages are persisted durably first.
- The durable memory projection is updated with its existing optimistic `memory_version` check.
- After a successful durable update, the hot entry is refreshed with the new version and bounded data.
- If the cache write fails, the durable write still succeeds; the next read repopulates the cache.
- A stale cache entry must never overwrite PostgreSQL or replace a newer cache version.
- Cache invalidation is scoped to the complete conversation identity and is required when a conversation is closed or its memory is reset.

The first implementation will use a single serialized value per conversation rather than multiple Redis structures. This keeps version checks, summary, facts, and recent messages atomic from the application's perspective.

## Redis key and value

Key format:

```text
ford:memory:v1:{tenantId}:{channel}:{providerAccountId}:{participantId}:{conversationId}
```

The value contains:

- schema version;
- memory version;
- summary;
- bounded recent messages;
- bounded durable facts;
- updated-at timestamp.

All user-provided components are encoded safely before being placed in the key. The value is bounded by the existing memory limits so a single active conversation cannot consume unbounded Redis memory.

## Expiration and configuration

The default cache policy is a 24-hour sliding TTL for active sessions. A successful read refreshes the TTL. PostgreSQL retains the complete durable history and projection beyond that period.

Configuration:

```yaml
ford:
  memory:
    cache-enabled: ${FORD_MEMORY_CACHE_ENABLED:false}
    cache-ttl: ${FORD_MEMORY_CACHE_TTL:PT24H}
```

Redis connection settings continue to use Spring Boot configuration:

```dotenv
FORD_REDIS_HOST=...
FORD_REDIS_PORT=6379
FORD_REDIS_PASSWORD=...
```

The default remains disabled for local environments that do not run Redis. Deployment environments can enable it explicitly.

## Security and privacy

- Redis must require authentication in deployed environments.
- No memory contents, tokens, prompts, or message bodies are written to application logs.
- Tenant, channel, provider-account, participant, and conversation identity are all part of the key scope.
- Redis is treated as sensitive transient storage and protected with the same network controls as PostgreSQL.
- TTL is a latency policy, not a retention policy; durable retention remains governed by PostgreSQL policy.

## MCP interaction

MCP remains a live-tool boundary for catalog, offers, inventory, provenance, and approved business capabilities. MCP results are not copied wholesale into hot memory. Only the final assistant response and explicitly confirmed facts may enter the durable memory projection. Tool payloads remain observable through redacted traces and metrics.

## Observability

Add low-cardinality metrics and tracing for:

- `ford.memory.cache.hit`;
- `ford.memory.cache.miss`;
- `ford.memory.cache.stale`;
- `ford.memory.cache.failure`;
- cache read/write latency;
- fallback-to-postgres count.

Metric tags must not include conversation IDs, participant identifiers, message content, or tokens. Logs should include only the outcome, cache operation, and exception type when relevant.

## Testing

Unit tests will cover:

- stable and tenant-isolated key construction;
- cache hit, miss, stale-version, expiration, and serialization behavior;
- PostgreSQL fallback when Redis is unavailable;
- write-through refresh after a durable memory update;
- invalidation on reset/close;
- bounded recent messages and facts;
- cache disabled behavior.

Integration tests will verify the Redis adapter against the project Redis service and confirm that a conversation can be loaded after cache eviction from PostgreSQL without changing the assistant response contract.

## Out of scope

- Moving durable memory from PostgreSQL to Redis.
- Storing the full conversation history in Redis.
- Using Redis as a catalog or offers cache.
- Implementing MCP memory tools.
- Introducing WebFlux or Spring AI streaming.
- Changing the existing Spring Modulith event contract.
