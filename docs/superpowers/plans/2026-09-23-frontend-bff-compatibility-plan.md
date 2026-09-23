# Frontend BFF Compatibility Implementation Plan

| Field | Detail |
|---|---|
| Design | [Frontend BFF Compatibility Design](../specs/2026-09-23-frontend-bff-compatibility-design.md) |
| Contract | [`ford-ai-service/docs/openapi/frontend-api.yaml`](../../../ford-ai-service/docs/openapi/frontend-api.yaml) |
| Branch | `feat/ford-service-mvp` |
| Status | Planned |

## Goal

Expose the frontend-requested `/api` contract while preserving the canonical
`/api/v1` API, tenant isolation, JWT authorities, idempotency, optimistic
concurrency, durable memory, and truthful metrics.

## Phase 1 — Contract and compatibility foundation

1. Write an OpenAPI contract test that validates OpenAPI 3.1.0, operation IDs,
   security schemes, required mutation headers, and implementation markers.
2. Update `frontend-api.yaml` to describe the `/api` facade, retain canonical
   `/api/v1` routes, add `/api/events/stream`, and correct the security model.
3. Add compatibility DTOs and mappers for conversation, message, event, AI,
   health, and analytics responses.
4. Add `FrontendTenantContext`/shared tenant resolution so every facade route
   uses the validated JWT and rejects ambiguous multi-tenant tokens.
5. Add MVC tests for authentication, authorities, tenant isolation, state
   mapping, and RFC 9457 errors.

## Phase 2 — Health and AI gateway

1. Add a public `/api/health` endpoint backed by a health/readiness service.
2. Add `/api/ai/respond` over `AssistantService`.
3. Extend AI execution result plumbing to expose safe latency, provider/model,
   classification, handoff, and nullable token usage.
4. Ensure raw prompts, system prompts, raw provider responses, PII, and secrets
   are not returned or persisted by the public gateway.
5. Add Test Lab authorization tests for failure injection and timeout mapping.

## Phase 3 — Conversation compatibility facade

1. Add GET `/api/conversations` with the frontend filters and bounded cursor
   pagination. Ignore `role` as an authorization input.
2. Add GET `/api/conversations/{id}` and GET `/messages` using existing query
   ports and explicit state/sender/delivery mappings.
3. Add POST `/messages` with `Idempotency-Key` and `If-Match`; preserve `202`
   async delivery semantics and internal-note behavior.
4. Add POST `/takeover` with `takeover=true|false`, mapping to existing
   handoff/handoff-back commands and respecting the human-handoff feature flag.
5. Add PATCH `/temperature` through an application command that records source
   `HUMAN`, updates the lead projection, and emits an audit event.
6. Add tests for duplicate requests, stale versions, closed conversations,
   human-active conflicts, tenant leakage, and webhook-created messages.

## Phase 4 — Durable runtime bot configuration

1. Red test: tenant-scoped read/write, validation, optimistic versioning,
   authorization, and audit behavior.
2. Add Liquibase migration 012 for `bot_configuration` with safe rollback and
   production-compatible defaults.
3. Add `BotConfigurationRepository` and application service using `JdbcClient`.
4. Make environment configuration the default and database configuration the
   runtime override without storing provider secrets.
5. Implement GET/PUT `/api/bot/config`; require `bot:manage` and `If-Match` on
   updates.
6. Add integration tests for migration, tenant isolation, rollback behavior,
   concurrent updates, and restart durability.

## Phase 5 — Analytics and telemetry compatibility

1. Add read-only compatibility projections for `/api/analytics/kpis` and
   `/api/analytics/telemetry` over existing metrics and `ai_execution` facts.
2. Add Liquibase migration 013 only for missing bounded telemetry dimensions;
   preserve existing facts and avoid raw content.
3. Add authoritative/unavailable financial measure fields. Do not populate
   revenue or margin until a live dealership adapter supplies them.
4. Support `7d`, `30d`, `month`, and `quarter` by translating to bounded UTC
   ranges; reject invalid or excessive periods.
5. Add tests for empty periods, nullable financial fields, token-null provider
   responses, cost estimation configuration, and tenant isolation.

## Phase 6 — Events, OpenAPI, and operational hardening

1. Add `/api/events` compatibility mapping and `/api/events/stream` SSE
   forwarding with bounded event payloads.
2. Add event type/status filters and cursor/page limits.
3. Add contract-to-controller integration tests covering every implemented
   operation and every documented status code.
4. Add a tenant-isolation Testcontainers suite spanning conversations, leads,
   bot config, metrics, telemetry, and events.
5. Document BFF forwarding, JWT claims, required headers, SSE proxying,
   environment variables, migration startup, and database reset commands.
6. Run `./gradlew clean check`, OpenAPI parsing, schema integration tests, and
   the complete integration suite.

## Acceptance criteria

- Frontend can call the documented `/api` paths through the BFF without path
  translation beyond forwarding the bearer token.
- Existing `/api/v1` clients continue to pass unchanged.
- No endpoint authorizes from `role`, `sender`, dealership headers, or tenant
  headers supplied by the browser.
- Bot configuration survives restart, is tenant-scoped, audited, and cannot
  store secrets.
- Message and temperature mutations remain idempotent and version-aware.
- AI and analytics responses never fabricate financial values or expose raw
  prompts/provider payloads.
- Database migrations run from a fresh database and an existing database;
  rollback scripts are syntactically valid.
- Full unit, MVC, schema, and Testcontainers integration suites pass.

## Explicitly deferred

- Live dealership HTTP adapters and CRM/finance data synchronization.
- Redis-backed multi-instance SSE fanout.
- Frontend implementation and Vercel deployment.
- Full bot prompt editor with raw prompt diagnostics.
