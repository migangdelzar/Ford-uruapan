# Redis Hot Conversation Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-open Redis hot-memory cache for active conversations while preserving PostgreSQL as the durable source of truth.

**Architecture:** A `RedisCachedConversationMemoryPort` decorates the existing JDBC `ConversationMemoryPort`. It uses a `ConversationMemoryCache` abstraction backed by one Redis hash per conversation, with a version field and serialized bounded memory context. Redis is enabled conditionally through `ford.memory.cache-enabled`; failures fall back to PostgreSQL.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Data Redis `StringRedisTemplate`, Jackson `ObjectMapper`, Micrometer, JUnit 5, AssertJ, Testcontainers Redis 1.20.6, PostgreSQL/pgvector.

## Global Constraints

- PostgreSQL remains the durable source of truth.
- Redis is fail-open and must never be required for a conversation to complete.
- The cache key includes tenant, channel, provider account, participant, and conversation identity.
- Cache entries contain only bounded summary, facts, recent messages, and memory version.
- No message content, prompts, tokens, or cache payloads may be logged.
- The default local cache setting remains disabled; deployment templates explicitly enable it.
- Keep Spring MVC, virtual threads, Spring Modulith, MCP, and synchronous ChatClient unchanged.

---

### Task 1: Define the cache boundary and write failing unit tests

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryCache.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/RedisConversationMemoryCache.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/RedisCachedConversationMemoryPort.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/RedisCachedConversationMemoryPortTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/conversations/RedisConversationMemoryCacheTest.java`

**Interfaces:**
- `ConversationMemoryCache.load(ConversationMemoryKey key)` returns `Optional<ConversationMemoryContext>`.
- `ConversationMemoryCache.store(ConversationMemoryContext context, Duration ttl)` stores a bounded context.
- `ConversationMemoryCache.evict(ConversationMemoryKey key)` removes one conversation entry.
- `RedisCachedConversationMemoryPort` implements `ConversationMemoryPort` and delegates durable work to a supplied durable port.

- [x] **Step 1: Write failing decorator tests.** Cover cache hit without durable read, durable load plus cache store on miss, durable fallback when cache read fails, eviction after durable message/projection writes, and filtering the current provider message plus the requested recent-message limit on cache hits. Use a fake cache and Mockito for the durable port.
- [x] **Step 2: Write failing Redis adapter tests.** Assert complete identity-based keys, serialized payload plus memory version, configured TTL, tenant/participant isolation, and invalid payload treated as a miss.
- [x] **Step 3: Run the focused tests and verify RED.**

```bash
./gradlew test --tests com.ford.service.conversations.RedisCachedConversationMemoryPortTest --tests com.ford.service.conversations.RedisConversationMemoryCacheTest --no-daemon --max-workers=1
```

Expected result: compilation/test failure because the cache boundary and implementations do not exist yet.

### Task 2: Implement cache-aside behavior and Redis storage

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryCache.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryPort.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/RedisConversationMemoryCache.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/adapter/out/RedisCachedConversationMemoryPort.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/RedisCachedConversationMemoryPortTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/RedisConversationMemoryCacheTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java`

- [x] **Step 1: Implement identity-safe keys.** Use `ford:memory:v1:{tenantId}:{channel}:{providerAccountId}:{participantId}:{conversationId}` with deterministic URL-safe encoding for variable components.
- [x] **Step 2: Implement Redis hash storage.** Use `StringRedisTemplate` with fields `memoryVersion` and `payload`, refresh the configured TTL, reject missing fields/invalid JSON/invalid versions, and keep Redis exceptions inside the adapter boundary.
- [x] **Step 3: Implement reads.** Try Redis, filter `currentProviderMessageId`, retain the newest `recentMessageLimit` messages in chronological order, and fall back to the durable delegate on miss or failure.
- [x] **Step 4: Implement writes.** Call the durable delegate first for `recordInbound`, `recordOutbound`, and `updateProjection`; evict only after the durable call returns. Cache eviction failures must not change the durable result.
- [x] **Step 5: Keep logging safe.** Log only operation, outcome, and exception type; never log keys, payloads, identifiers, or message content.
- [x] **Step 6: Run the focused tests and verify GREEN.** Re-run the Task 1 command and require all focused tests to pass.

### Task 3: Add properties, conditional Spring wiring, and metrics

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/MemoryProperties.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/MemoryPropertiesTest.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationConfigurationSliceTest.java`

- [x] **Step 1: Add `Duration cacheTtl` with positive validation.** Default it to 24 hours and expose it through `cacheTtl()`.
- [x] **Step 2: Add configuration.** Use `ford.memory.cache-enabled=${FORD_MEMORY_CACHE_ENABLED:false}` and `ford.memory.cache-ttl=${FORD_MEMORY_CACHE_TTL:PT24H}`.
- [x] **Step 3: Wire conditionally.** When enabled, create a `ConversationMemoryCache` backed by `StringRedisTemplate` and a `@Primary ConversationMemoryPort` backed by the existing JDBC repository plus the cache. When disabled, keep JDBC as the selected port.
- [x] **Step 4: Add low-cardinality metrics.** Record counters/timers for hit, miss, stale, failure, load, store, and evict. Tags may include operation/outcome only.
- [x] **Step 5: Test both wiring modes.** Verify the disabled context has no Redis decorator and the enabled context selects the decorator when Redis infrastructure is present.
- [x] **Step 6: Run focused configuration tests.**

```bash
./gradlew test --tests com.ford.service.conversations.MemoryPropertiesTest --tests com.ford.service.conversations.ConversationConfigurationSliceTest --no-daemon --max-workers=1
```

### Task 4: Add Redis integration coverage

**Files:**
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/RedisConversationMemoryCacheIntegrationTest.java`

- [x] **Step 1: Reuse the existing Testcontainers core dependency.** A Redis-specific module is not required for `GenericContainer`; the existing integration-test source set and dependency inheritance remain unchanged.
- [x] **Step 2: Test against `redis:7-alpine`.** Start a container, create a `LettuceConnectionFactory` and `StringRedisTemplate`, store/load a context, verify identity and positive TTL, stale-version protection, and eviction.
- [x] **Step 3: Run the focused integration test.**

```bash
DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
GRADLE_OPTS="-Ddocker.host=unix://${HOME}/.colima/default/docker.sock" \
./gradlew integrationTest --tests com.ford.service.conversations.RedisConversationMemoryIntegrationTest --no-daemon --max-workers=1
```

Require zero failures and zero errors after implementation.

### Task 5: Enable and document hot memory for deployment

**Files:**
- Modify: `ford-ai-service/.env.example`
- Modify: `ford-ai-service/.env.prod.example`
- Modify: `deployment/runtime/app/.env.template`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Create: `docs/redis-hot-memory.md`

- [x] **Step 1: Add `FORD_MEMORY_CACHE_TTL=PT24H` to examples.** Keep local cache disabled by default; document production enablement.
- [x] **Step 2: Enable cache in VM app templates.** Add `FORD_MEMORY_CACHE_ENABLED=true` and `FORD_MEMORY_CACHE_TTL=PT24H`, preserving existing emme-2 Redis host, port, and password wiring.
- [x] **Step 3: Document Redis restart/eviction behavior.** State that Redis loss causes a PostgreSQL fallback and does not delete durable conversation history.
- [x] **Step 4: Run available shell syntax checks.** `bash -n` passed; Ansible syntax validation was skipped because `ansible-playbook` is not installed locally.

### Task 6: Full verification and commit

**Files:**
- No additional production files; verify the complete change set.

- [x] **Step 1: Run `./gradlew test --no-daemon --max-workers=1`.**
- [x] **Step 2: Run the complete `integrationTest` suite with the Docker socket overrides from Task 4.** Require zero failures and errors.
- [x] **Step 3: Run available shell syntax checks.** No Node project is present in this worktree; `bash -n` passed.
- [x] **Step 4: Review the diff for secrets, generated files, identifiers, and unrelated changes.**
- [ ] **Step 5: Commit with `feat: add Redis hot conversation memory`.** Stage only Redis implementation, tests, configuration/templates, documentation, and the already committed design/plan files.
