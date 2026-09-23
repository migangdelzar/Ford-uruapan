# Frontend `/api/v1` Contract Design

## Status

Proposed on 2026-09-23. This design establishes `/api/v1` as the only
frontend/operator API surface for this new application. No compatibility
facade or unversioned operator routes will be added.

## Goal

Make the OCI backend consumable by the frontend contract supplied on
2026-09-23 under a single `/api/v1` namespace without weakening tenant isolation, JWT authorization,
idempotency, optimistic concurrency, webhook security, or metric integrity.

## Decisions

1. Keep `/api/v1` as the canonical, versioned operator API.
2. Reuse application ports and repositories. Controllers must not query provider
   tables or duplicate conversation/handoff state transitions.
4. Derive tenant, role, and permissions only from the validated JWT. Ignore
   client-provided `role`, `sender`, dealership, and tenant authorization hints.
5. Preserve `Idempotency-Key` and `If-Match` on mutations. The compatibility
   contract will document them as required headers, even where the initial
   frontend draft omitted them.
6. Persist bot configuration per tenant. Environment variables remain startup
   defaults; database values are the runtime override.
7. Report only authoritative metrics. CRM-derived revenue and margin values
   are nullable/unavailable until a configured dealership adapter supplies them.
8. Keep chaos/failure injection behind the existing Test Lab property and
   `testlab:run` authority. `/api/ai/respond` cannot activate injectors in
   normal production traffic.
9. Use RFC 9457 `ProblemDetail` for all errors, including compatibility routes.
10. Use OpenAPI 3.1.0 with `servers.url` ending in `/api/v1`; path keys do not
    repeat `/api/v1`.

## Surface mapping

| Requested capability | Implementation | Notes |
|---|---|---|
| `/api/v1/health` | New health endpoint | Public liveness/readiness; no secret or key value disclosure |
| `/api/v1/ai/respond` | New AI endpoint over `AssistantService` | Safe diagnostics only; no raw prompt/completion by default |
| `/api/v1/conversations` | Existing `ConversationQuery` | JWT tenant context; bounded pagination |
| `/api/v1/conversations/{id}` | Existing `ConversationQuery` | Explicit internal-state to UI-state mapping |
| `/api/v1/conversations/{id}/messages` | Existing query/command ports | GET page; POST remains idempotent/version-aware |
| `/api/v1/conversations/{id}/takeover` | Existing `HandoffService` | `takeover=true/false` maps to takeover/handback |
| `/api/v1/conversations/{id}/fields` | New lead/prospect application command | Durable structured prospect fields |
| `/api/v1/bot/config` | Existing redacted GET plus persisted PUT | Tenant-scoped runtime settings and audit event |
| `/api/v1/metrics` | Existing metrics query services | No fabricated financial values |
| `/api/v1/events` | Existing audit query | Add event type/status filters and bounded pagination |
| `/api/v1/events/stream` | Existing SSE broker | Single-instance until Redis adapter is configured |

Meta webhooks remain outside `/api/v1` because Meta requires their public
verification and signed provider callback shape.

## Database changes

### Migration 012: runtime bot configuration

Create `bot_configuration`:

- `tenant_id uuid primary key`
- `version bigint not null default 0`
- `system_prompt text` (bounded by application validation; never returned to
  unauthorized callers)
- `model varchar(160) not null`
- `creativity numeric(4,3) not null`
- `mode varchar(32) not null` (`AUTO_REPLY`, `SUGGEST_ONLY`, `OFF`)
- `handoff_keywords jsonb not null`
- `handoff_on_unsure boolean not null`
- `response_delay_ms integer not null`
- `updated_by varchar(240) not null`
- `updated_at timestamptz not null`

Add a tenant/time index if configuration history is later split into an
append-only audit table. The first version records changes in the existing
audit-event stream to avoid duplicate audit storage.

### Migration 013: AI telemetry read projection

The existing `ai_execution` table is the source for telemetry. Add only what is
needed for the requested report, with safe nullable semantics:

- `request_kind varchar(64)`
- `channel varchar(32)`
- `outcome varchar(32)`
- `estimated_cost_usd numeric(18,8)` if cost calculation is configured
- bounded indexes by `(tenant_id, created_at)` and `(tenant_id, channel,
  created_at)`

Do not add raw messages, prompts, completions, API keys, phone numbers, or
unbounded provider metadata.

### Financial data rule

The analytics DTO must represent financial values as an object with
`value`, `currency`, `available`, and `source`. If the dealership adapter is
unconfigured, `available=false` and `value=null`. The API must never return
synthetic totals such as `$3.84M MXN`.

## Security model

- Public: `/api/health`, Meta verification, and signed Meta webhook POSTs.
- Authenticated: all operator, AI, analytics, bot, event, and conversation
  routes.
- Authorities: `SCOPE_operator:read`, `SCOPE_operator:write`,
  `SCOPE_handoff:manage`, `SCOPE_bot:manage`, `SCOPE_metrics:read`,
  `SCOPE_events:read`, and `SCOPE_testlab:run`.
- Roles remain coarse grants (`ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_OPERATOR`),
  but endpoint rules prefer explicit authorities.
- Tenant selection comes from `tenant_id`/`tenant_ids` JWT claims. If a token
  contains multiple tenants and the request does not provide a non-authorizing
  tenant selector, return `400`/`422` rather than guessing.
- `role` query parameters are removed from authorization decisions.
- `sender` in a message request is ignored or rejected; the server derives
  sender identity from the authenticated principal.

## AI response safety

`/api/v1/ai/respond` accepts conversation context and returns reply text,
classification, handoff recommendation, extracted lead fields, provider/model,
latency, and token counts when available. It does not return system prompts or
raw provider responses by default. A separate privileged diagnostic view may
be added later for Test Lab only.

The request's `failureInjectors` are accepted only when Test Lab is enabled and
the authenticated caller has `testlab:run`; otherwise the request is rejected
with `403` or the fields are ignored according to the final contract decision.
The plan chooses rejection to prevent silent test behavior in production.

## Configuration precedence

1. Environment variables provide startup defaults and secrets.
2. A tenant's persisted `bot_configuration` overrides non-secret runtime bot
   settings.
3. Missing database configuration falls back to environment-derived defaults
   and is reported as `source=environment` in the redacted configuration view.
4. Provider credentials never enter the database configuration table or API
   response.

## Error and compatibility rules

- `401`: missing/invalid JWT.
- `403`: valid JWT without required authority or tenant access.
- `404`: resource absent in the caller's tenant; do not disclose cross-tenant
  existence.
- `409`: stale version, idempotency conflict, or invalid state transition.
- `422`: semantically invalid request, closed Meta window, or invalid bot
  configuration.
- `503`: configured port has no live upstream adapter.
- `504`: bounded AI/upstream timeout.

All responses use the existing `ProblemDetail` envelope with stable `code`,
`path`, `traceId`, and `correlationId` properties.

## Non-goals for this release

- Implementing the frontend or Vercel/BFF proxy.
- Making the backend issue JWTs; OIDC remains the identity provider.
- Returning fabricated CRM financial metrics.
- Enabling human handoff when `FORD_HUMAN_HANDOFF_ENABLED=false`.
- Replacing the in-process SSE broker with Redis in the same change. The
  adapter seam and deployment limitation remain documented.
