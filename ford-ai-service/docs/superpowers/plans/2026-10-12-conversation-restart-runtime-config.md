# Conversation Restart and Runtime Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Let an advisor restart one conversation's bot context from Ford-web, preserve its transcript, and serve validated tenant business settings from PostgreSQL through Redis.

**Architecture:** PostgreSQL remains authoritative for reset epochs and tenant settings. Redis caches effective settings and hot conversation memory; a restart persists the new epoch first, then evicts the conversation cache and publishes a metadata-only SSE refresh. The restart command uses tenant authorization, idempotency, optimistic versioning, and the existing bot-turn lease.

**Tech Stack:** Java 25, Spring Boot, Spring Security, Spring JDBC `JdbcClient`, Liquibase, PostgreSQL/pgvector, Redis, React, TypeScript, Vitest.

## Global Constraints

- Transcript rows remain available after a bot-context restart.
- Pre-epoch messages, summaries, facts, and semantic memories cannot enter later bot context.
- PostgreSQL is durable source of truth; Redis outages fall back to PostgreSQL.
- Runtime configuration is a strict allowlist of validated business settings; credentials and infrastructure values stay in environment/secret management.
- Restart requires tenant authorization, `If-Match`, CSRF protection, and an idempotency key.
- Migrations and deployment do not reset production chats.

---

## File Map

### Ford-service

- `src/main/resources/db/changelog/changes/023-conversation-runtime-settings-and-memory-epoch.yaml`: runtime settings table and reset epoch fields.
- `src/main/resources/db/changelog/db.changelog-master.yaml`: register migration 023.
- `src/main/java/com/ford/service/conversations/domain/ConversationMemoryContext.java`: include context epoch in cached memory payload.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryPort.java`: add durable restart operation.
- `src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationMemoryRepository.java`: load only current-epoch context and atomically reset projections and semantic memories.
- `src/main/java/com/ford/service/conversations/adapter/out/RedisCachedConversationMemoryPort.java`: evict conversation memory after a successful durable reset.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java`: expose restart and use effective runtime memory limits.
- `src/main/java/com/ford/service/conversations/application/ConversationRuntimeSettings.java`: validated runtime settings record.
- `src/main/java/com/ford/service/conversations/application/ConversationRuntimeSettingsService.java`: defaults, validation, read/update, and revision management.
- `src/main/java/com/ford/service/conversations/application/ConversationRuntimeSettingsCache.java`: cache port.
- `src/main/java/com/ford/service/conversations/adapter/out/JdbcConversationRuntimeSettingsRepository.java`: durable JSONB persistence and compare-and-set updates.
- `src/main/java/com/ford/service/conversations/adapter/out/RedisConversationRuntimeSettingsCache.java`: versioned Redis cache, TTL, and fail-open behavior.
- `src/main/java/com/ford/service/conversations/application/ConversationMemoryRestartService.java`: authorize, acquire lease, version-check, reset, and publish event.
- `src/main/java/com/ford/service/conversations/adapter/in/web/ConversationController.java`: `POST /{conversationId}/restart`.
- `src/main/java/com/ford/service/conversations/adapter/in/web/ConversationRuntimeSettingsController.java`: authorized `GET`/`PUT` runtime settings.
- `src/main/java/com/ford/service/realtime/application/ConversationRealtimeListener.java`: metadata-only SSE update after reset commit.
- Unit/repository/integration tests under `src/test/java/com/ford/service/conversations` and `src/test/java/com/ford/service/realtime`.

### Ford-web

- `src/shared/api/operatorCrmApi.ts`: typed restart and runtime-settings clients with CSRF/idempotency headers.
- `src/widgets/conversation/ui/ConversationWidget.tsx`: restart request state, confirmation, refresh, and conflict handling.
- `src/entities/conversation/ui/ConversationHeader.tsx`: “Reiniciar contexto del bot” action.
- `src/widgets/bot-config/ui/BotConfigWidget.tsx`: read and save supported backend runtime settings instead of treating browser local storage as production source of truth.
- `src/store/useCrmStore.ts`: keep local demo settings only when unauthenticated; authenticated writes use backend API.
- `src/shared/api/openapi.yaml`: runtime settings and restart contracts.
- `src/server/auth/createBffApp.ts`: permit only the specific runtime-settings methods/path if the BFF route remains enabled.
- English/Spanish strings and focused Vitest files for both widgets and API clients.

## Task 1: Add the Memory Epoch and Safe Durable Reset

**Interface:** `ConversationMemoryPort.resetContext(ConversationMemoryKey key, UUID actorId, Instant resetAt)` persists the context epoch. The application restart service returns `ConversationMemoryRestartReceipt`; existing message and transcript APIs remain unchanged.

- [x] **Step 1: Write failing repository tests** in `JdbcConversationMemoryRepositoryTest` for excluding old messages, clearing summary/facts, deactivating old semantic memories, and retaining transcript rows.
- [x] **Step 2: Run the tests** with `./gradlew test --tests '*JdbcConversationMemoryRepositoryTest' --no-daemon`; confirm failure because the epoch/reset API is missing.
- [x] **Step 3: Add Liquibase migration 023** with a `context_started_at` column defaulting to negative infinity and nullable `context_reset_by`; add tenant runtime config table with JSONB, revision, actor, and timestamp.
- [x] **Step 4: Register migration 023** in `db.changelog-master.yaml`.
- [x] **Step 5: Add `contextStartedAt` to `ConversationMemoryContext`** and update JDBC load queries to filter message rows at or after the active epoch.
- [x] **Step 6: Implement the JDBC reset transaction** to lock the scoped conversation, clear summary/facts/source pointer, increment memory version, set actor/time, and deactivate earlier `conversation_memory_item` and `conversation_memory_embedding` rows. Do not delete `conversation_message` rows.
- [x] **Step 7: Run the repository tests** and verify transcript lookup still returns old messages while assistant memory does not.

## Task 2: Implement Tenant Runtime Settings With Redis Cache

**Interface:** `ConversationRuntimeSettingsService.current(UUID tenantId)` returns effective settings and revision. `update(UUID tenantId, UUID actorId, long expectedRevision, ConversationRuntimeSettings requested)` validates, persists, refreshes the shared Redis key, and returns the new revision. The live allowlist is handoff enabled, memory enabled, and prompt recent-message limit (1–50); other settings remain deployment configuration until they have runtime consumers.

- [x] **Step 1: Write failing tests** for defaults, allowed values, invalid ranges, optimistic revision conflict, cache hit, database fallback, and Redis unavailable behavior.
- [x] **Step 2: Run the tests** with `./gradlew test --tests '*ConversationRuntimeSettingsServiceTest' --no-daemon`; confirm they fail on missing service behavior.
- [x] **Step 3: Implement a typed settings record** for the live-supported handoff enablement, memory enablement, and prompt recent-message limit (1–50).
- [x] **Step 4: Implement JDBC JSONB storage** with tenant-scoped revision compare-and-set and actor/timestamp audit fields. Merge omitted fields with the current validated configuration.
- [x] **Step 5: Implement Redis caching** under `ford:runtime-settings:v1:{tenantId}` with a short TTL and serialized revision. Redis miss reads PostgreSQL; Redis error falls back to PostgreSQL without failing the conversation.
- [x] **Step 6: Implement update cache refresh** after PostgreSQL commit. Refresh the shared tenant Redis key (60-second TTL) so all instances use the current revision; PostgreSQL remains authoritative and Redis failures fall back to PostgreSQL/defaults.
- [x] **Step 7: Add the authorized runtime-settings GET/PUT endpoint** with tenant membership validation, configuration authority, `If-Match` revision check, and an audit actor from Spring Security.
- [x] **Step 8: Route memory/handoff runtime decisions through the service** while retaining existing application properties as defaults when no database row exists.
- [x] **Step 9: Verify repeated reads hit Redis** after the initial cache fill, and all tests pass.

## Task 3: Connect the Ford-web Configuration UI

**Interface:** `getConversationRuntimeSettings(tenantId)` and `updateConversationRuntimeSettings(tenantId, revision, settings)` map to the authorized GET/PUT endpoints and return typed revisioned settings.

- [x] **Step 1: Write failing API tests** for CSRF, tenant scope, revision headers, response validation, and server errors.
- [x] **Step 2: Write a failing config-widget test** proving authenticated settings are loaded from the server and save persists through the API rather than local storage.
- [x] **Step 3: Run focused tests** with `npm run test -- --run src/shared/api/operatorCrmApi.test.ts src/widgets/bot-config/ui/BotConfigWidget.test.tsx` from Ford-web; confirm failure.
- [x] **Step 4: Implement the typed API client** using same-origin credentials and the existing `readCsrfToken` helper.
- [x] **Step 5: Connect supported settings controls** to the API. Keep local-storage defaults only for unauthenticated demo mode; do not claim unsupported controls changed backend behavior.
- [x] **Step 6: Update OpenAPI and BFF allowlists** for the exact GET/PUT paths; run `npm run api:check`.
- [x] **Step 7: Run focused widget/API tests and `npm run lint`.**

## Task 4: Add the Protected Per-Conversation Restart Command

**Interface:** `ConversationMemoryRestartService.restart(operator, tenantId, conversationId, expectedVersion, idempotencyKey)` returns `ConversationMemoryRestartReceipt(conversationId, version, contextStartedAt)`.

- [x] **Step 1: Write failing service tests** for allowed bot state, active turn lease, stale version, unauthorized tenant, idempotent replay, and rejection of human-owned/closed conversations.
- [x] **Step 2: Run tests** with `./gradlew test --tests '*ConversationMemoryRestartServiceTest' --no-daemon`; confirm expected failures.
- [x] **Step 3: Implement restart service** using `ConversationTurnLeasePort`, `OperatorConversationCommandRepository`, existing idempotency storage, memory reset port, and memory reset port. Reject if the bot turn lease is held; release only leases acquired by this request.
- [x] **Step 4: Add `POST /api/v1/conversations/{conversationId}/restart`** with tenant scope, operator-write/admin authority, required `If-Match`, CSRF-compatible POST, and `Idempotency-Key`.
- [x] **Step 5: Publish a post-commit reset event** that emits `conversation.updated` with only status, reset flag, version, and time. The reset port evicts Redis memory immediately after durable reset and treats eviction failure as an error so the reset transaction does not report success with stale cached context.
- [x] **Step 6: Test authorization, active-turn conflict, stale version, duplicate requests, and no-text SSE payload.**

## Task 5: Add the Restart Action to the Conversation UI

**Interface:** `restartConversationContext(tenantId, conversationId, version)` returns the typed reset receipt.

- [x] **Step 1: Write failing API/widget tests** for CSRF and idempotency headers, confirmation, successful refresh, and active-turn conflict.
- [x] **Step 2: Run focused tests** with `npm run test -- --run src/shared/api/operatorCrmApi.test.ts src/widgets/conversation/ui/ConversationWidget.test.tsx` and confirm they fail.
- [x] **Step 3: Add “Reiniciar contexto del bot”** to the selected conversation header. Confirmation explicitly says the transcript stays visible while the bot forgets prior context.
- [x] **Step 4: On success, reload the conversation and transcript** and show a success toast. Disable the control during requests and for human-owned/closed states.
- [x] **Step 5: On active-turn conflict, preserve the transcript** and show a retryable message without automatic retry.
- [x] **Step 6: Add Mexican Spanish and English labels and run focused tests plus `npm run lint`.**

## Task 6: Full Verification

- [x] **Step 1: Run Ford-service unit tests** with `./gradlew test --no-daemon`.
- [x] **Step 2: Run Postgres/Redis integration tests** with `./gradlew integrationTest --no-daemon` (PostgreSQL/Redis integration suite).
- [x] **Step 3: Run Ford-web tests and lint** with `npm run test`, `npm run lint`, and `npm run build`.
- [x] **Step 4: Run `npm run api:check -- /Users/miguelangeldelgadillozarate/Development/Ford-uruapan/ford-ai-service/docs/openapi/frontend-api.yaml` and `git diff --check` in both repositories.**
- [x] **Step 5: Review migrations, authorization, cache fallback, event privacy, and confirm no production reset or deployment occurred.**

Verification completed locally: Ford-service unit and integration Gradle tasks succeeded; Ford-web 30 test files / 119 tests, TypeScript check, 47-path OpenAPI contract check, and production build succeeded. The build reports an existing large JavaScript chunk warning. No commit, push, deployment, or production chat reset was performed.

## Acceptance Criteria

- Advisor can restart the bot context from the selected conversation while transcript messages remain visible and durable.
- An active bot turn prevents reset and produces an actionable conflict.
- The next inbound turn uses only messages and memory from the new epoch.
- Settings use Redis after cache fill, are validated and audited in PostgreSQL, and remain available if Redis fails.
- Settings updates propagate across service instances and report revision conflicts rather than overwriting newer values.
- Only settings with live runtime consumers appear as editable controls in Ford-web.
- No credentials or infrastructure settings are stored in the runtime configuration row.
- No production chat is reset during implementation or deployment.
