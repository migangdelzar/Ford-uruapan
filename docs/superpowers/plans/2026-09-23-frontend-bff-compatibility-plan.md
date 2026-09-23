# Frontend `/api/v1` Contract Implementation Plan

| Field | Detail |
|---|---|
| Design | [Frontend BFF Compatibility Design](../specs/2026-09-23-frontend-bff-compatibility-design.md) |
| Contract | [`ford-ai-service/docs/openapi/frontend-api.yaml`](../../../ford-ai-service/docs/openapi/frontend-api.yaml) |
| Branch | `feat/ford-service-mvp` |
| Status | Planned |

## Goal

Expose the frontend-requested contract only under `/api/v1`, with tenant
isolation, JWT authorities, idempotency, optimistic concurrency, durable
memory, and truthful metrics.

## Phase 1 — Contract and canonical API foundation

1. Write an OpenAPI contract test that validates OpenAPI 3.1.0, operation IDs,
   security schemes, required mutation headers, and implementation markers.
2. Update `frontend-api.yaml` to use servers ending in `/api/v1`, remove
   duplicated `/api/v1` path prefixes, and correct the security model.
3. Add canonical DTOs and mappers for conversation, message, event, AI, health,
   and analytics responses.
4. Add shared tenant resolution so every canonical route uses the validated JWT
   and rejects ambiguous multi-tenant tokens.
5. Add MVC tests for authentication, authorities, tenant isolation, state
   mapping, and RFC 9457 errors.

## Phase 2 — Health and AI gateway

1. Add a public `/api/v1/health` endpoint backed by a health/readiness service.
2. Add `/api/v1/ai/respond` over `AssistantService`.
3. Extend AI execution result plumbing to expose safe latency, provider/model,
   classification, handoff, and nullable token usage.
4. Ensure raw prompts, system prompts, raw provider responses, PII, and secrets
   are not returned or persisted by the public gateway.
5. Add Test Lab authorization tests for failure injection and timeout mapping.

## Phase 3 — Conversation and prospect API

1. Add GET `/api/v1/conversations` with the frontend filters and bounded cursor
   pagination. Ignore `role` as an authorization input.
2. Add GET `/api/v1/conversations/{id}` and GET `/messages` using existing query
   ports and explicit state/sender/delivery mappings.
3. Add POST `/messages` with `Idempotency-Key` and `If-Match`; preserve `202`
   async delivery semantics and internal-note behavior.
4. Add POST `/takeover` with `takeover=true|false`, mapping to existing
   handoff/handoff-back commands and respecting the human-handoff feature flag.
5. Add PUT `/fields` through an application command that records source
   `HUMAN`, updates the durable prospect projection, and emits an audit event.
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
5. Implement GET/PUT `/api/v1/bot/config`; require `bot:manage` and `If-Match` on
   updates.
6. Add integration tests for migration, tenant isolation, rollback behavior,
   concurrent updates, and restart durability.

## Phase 5 — Analytics and telemetry

1. Add read-only `/api/v1/metrics` and `/api/v1/metrics/telemetry` projections
   over existing metrics and `ai_execution` facts.
2. Add Liquibase migration 013 only for missing bounded telemetry dimensions;
   preserve existing facts and avoid raw content.
3. Add authoritative/unavailable financial measure fields. Do not populate
   revenue or margin until a live dealership adapter supplies them.
4. Support `7d`, `30d`, `month`, and `quarter` by translating to bounded UTC
   ranges; reject invalid or excessive periods.
5. Add tests for empty periods, nullable financial fields, token-null provider
   responses, cost estimation configuration, and tenant isolation.

## Phase 6 — Events, OpenAPI, and operational hardening

1. Add `/api/v1/events` and `/api/v1/events/stream` with bounded event payloads.
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

- Frontend can call the documented `/api/v1` paths through the BFF without path
  translation beyond forwarding the bearer token.
- There is exactly one versioned operator API surface; no unversioned aliases
  are required.
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
