# Redis hot conversation memory

The Ford service keeps PostgreSQL as the durable source of truth for conversation
messages, summaries, facts, and memory versions. Redis is an optional hot cache
for active conversations. A Redis restart, eviction, timeout, or malformed entry
falls back to PostgreSQL and does not interrupt the conversation workflow.

## Configuration

Enable the cache only when the application can reach the authenticated Redis
instance:

```dotenv
FORD_MEMORY_CACHE_ENABLED=true
FORD_MEMORY_CACHE_TTL=PT24H
FORD_REDIS_HOST=10.0.0.29
FORD_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=change-me
```

`FORD_MEMORY_CACHE_TTL` is an ISO-8601 duration. The default is `PT24H`, and
each successful cache hit refreshes the entry's lifetime. Durable message or
projection writes evict the corresponding conversation entry so the next turn
reloads current state.

## Identity and safety

Each entry is scoped by tenant, channel, provider account, participant, and
conversation:

```text
ford:memory:v1:{tenantId}:{channel}:{providerAccountId}:{participantId}:{conversationId}
```

Variable identity components are URL-encoded. Values contain only the bounded
summary, durable facts, recent messages, and memory version. The cache never
stores provider credentials or raw webhook payloads.

## Verification

Run unit tests with:

```bash
./gradlew test --no-daemon --max-workers=1
```

Run the Redis integration test with Docker/Colima available. On Colima, expose
the Docker socket to Testcontainers:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew integrationTest --tests com.ford.service.conversations.RedisConversationMemoryCacheIntegrationTest \
  --no-daemon --max-workers=1
```

The integration test verifies a real Redis round trip, positive TTL, encoded
identity components, stale-version protection, and eviction.
