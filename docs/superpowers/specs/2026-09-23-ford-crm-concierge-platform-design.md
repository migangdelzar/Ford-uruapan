# Ford CRM Concierge Platform Design

## Status

Approved direction for design review. Implementation must wait until the user
reviews this document.

## Objective

Provide the frontend with a stable, secure operator API and complete
operational/business metrics while preserving the OCI Ford service as the
authoritative domain backend.

The system must support conversations, advisor operations, lead management,
AI orchestration, Meta delivery, dealership tools, realtime updates, audit
history, and executive reporting without exposing provider credentials,
tenant-spoofable headers, raw prompts, or raw model responses.

## Architecture

```text
Browser
  │ same-origin HTTPS and HttpOnly session cookie
  ▼
BFF / frontend backend
  │ OIDC session, response shaping, aggregation, SSE proxy
  │ authenticated server-to-server request
  ▼
OCI Ford service
  │ domain authorization, state transitions, outbox, AI, memory, persistence
  ├── PostgreSQL / pgvector
  ├── Meta Graph API
  ├── AI providers and TypeSafe/Jev (optional)
  └── Dealership APIs / CRM
```

The BFF owns the frontend-facing `/api/v1` contract. The OCI service owns
business truth and exposes resource/application APIs for the BFF. The BFF may
aggregate several OCI calls, but it must not maintain an independent copy of
conversation, lead, assignment, or delivery truth.

Meta webhooks call OCI directly. The browser never calls webhook endpoints.

## Responsibility boundaries

### Frontend

- Presentation, navigation, filters, forms, charts, and local UI state.
- Optimistic rendering with server reconciliation.
- Conversation/message/lead/advisor views.
- SSE consumption and reconnect handling.
- Display-safe AI telemetry only.
- No client secrets, refresh tokens, Meta tokens, tenant headers, or role
  headers.

### BFF

- Authorization Code + PKCE login integration.
- Secure HttpOnly browser session and server-side refresh token handling.
- Frontend-friendly DTOs and cursor pagination.
- Aggregated dashboard responses.
- SSE proxy and connection authorization.
- Rate limits, request validation, idempotency propagation, and correlation
  IDs.
- Translation between frontend status names and OCI domain states.

The BFF does not implement conversation state transitions, AI policy, Meta
window rules, delivery retry, or lead scoring.

### OCI backend

- JWT validation, roles, authorities, and tenant authorization.
- Conversation lifecycle, optimistic locking, and durable messages.
- Human takeover/handback locks and assignments.
- Lead profile, scoring, and qualification evidence.
- AI classification, response generation, memory, and tool policy.
- Meta webhook verification, normalization, deduplication, HSM, 24-hour
  windows, delivery receipts, and outbox retries.
- Inventory, finance, appointments, CRM synchronization, and provider ports.
- Durable audit and metric facts.

## Security model

The OCI service remains an OAuth2 resource server. It validates the JWT
issuer, audience, expiration, scopes, roles, and tenant claims.

Roles are mapped to Spring authorities:

```text
OPERATOR → ROLE_OPERATOR
MANAGER  → ROLE_MANAGER
ADMIN    → ROLE_ADMIN
```

Scopes are mapped to:

```text
operator:read       → SCOPE_operator:read
operator:write      → SCOPE_operator:write
handoff:manage      → SCOPE_handoff:manage
leads:read          → SCOPE_leads:read
leads:write         → SCOPE_leads:write
inventory:read      → SCOPE_inventory:read
finance:quote       → SCOPE_finance:quote
appointments:read   → SCOPE_appointments:read
appointments:write  → SCOPE_appointments:write
bot:read            → SCOPE_bot:read
bot:manage          → SCOPE_bot:manage
events:read         → SCOPE_events:read
metrics:read        → SCOPE_metrics:read
testlab:run         → SCOPE_testlab:run
```

The browser and BFF must never send `X-User-Role`, `X-Dealership-ID`, or
`X-Tenant-Id` as authority. Tenant access is derived from JWT claims and
enforced in the OCI application layer. If the BFF forwards a user token, the
OCI audience must be `ford-ai-service`; if it exchanges tokens, the exchanged
token must preserve subject, scopes, roles, and tenant claims.

## Frontend contract

The machine-readable contract remains
`ford-ai-service/docs/openapi/frontend-api.yaml`, but it is the target BFF
contract. Operations must be marked `implemented`, `planned`, or
`restricted` and must not imply capabilities that OCI does not provide.

### Phase 1 operator inbox

```text
GET   /api/v1/conversations
GET   /api/v1/conversations/{conversationId}
GET   /api/v1/conversations/{conversationId}/messages
POST  /api/v1/conversations/{conversationId}/messages
POST  /api/v1/conversations/{conversationId}/takeover
POST  /api/v1/conversations/{conversationId}/handback
POST  /api/v1/conversations/{conversationId}/assign
GET   /api/v1/realtime/stream
```

### Phase 2 lead and advisor operations

```text
GET   /api/v1/conversations/{conversationId}/lead
PATCH /api/v1/conversations/{conversationId}/lead
GET   /api/v1/team/advisors
GET   /api/v1/team/advisors/{advisorId}
GET   /api/v1/events/audit
```

### Phase 3 dealership capabilities

```text
GET  /api/v1/inventory
GET  /api/v1/vehicles/{vin}
POST /api/v1/finance/quotes
GET  /api/v1/appointments/available-slots
POST /api/v1/appointments
POST /api/v1/leads/sync
```

### Phase 4 Meta operations

```text
GET  /api/v1/conversations/{conversationId}/window
GET  /api/v1/meta/templates
POST /api/v1/conversations/{conversationId}/hsm-reopen
```

### Phase 5 administration and test lab

```text
GET  /api/v1/bot/config
PUT  /api/v1/bot/config
POST /api/v1/admin/ai/simulations
POST /api/v1/admin/test-scenarios/runs
```

Bot simulation, raw AI diagnostics, system prompts, and provider payloads are
restricted to authorized administrative users and are redacted by default.
They are not part of the normal advisor conversation response.

## Domain status mapping

The frontend uses display-oriented statuses while OCI retains domain states:

| Frontend status | OCI state |
|---|---|
| `bot` | `BOT_ACTIVE` or `LEAD_CAPTURE` |
| `waiting` | `HUMAN_PENDING` |
| `human` | `HUMAN_ACTIVE` |
| `closed` | `CLOSED` |
| `failed` | derived from delivery/outbox failure, not a conversation state |

The BFF performs this mapping. OCI state transitions remain atomic and
version-checked.

## Mutation safety

All mutating frontend operations must support:

```http
Authorization: Bearer <access-token>
Idempotency-Key: <client-generated-unique-key>
If-Match: "<conversation-version>"
X-Correlation-Id: <request-correlation-id>
```

Outbound messages return `202 Accepted` because delivery is asynchronous.
The response includes the durable message ID, queued status, and conversation
version. Stale versions return `409 Conflict`. Errors use
`application/problem+json` with a stable machine-readable code.

## Realtime model

The OCI service publishes domain events after durable state changes. The BFF
authorizes and proxies them as SSE events:

```text
conversation.created
conversation.updated
message.created
message.delivery.updated
handoff.created
handoff.updated
lead.updated
advisor.updated
metrics.updated
```

Events contain identifiers and display-safe summaries, not raw provider
payloads or secrets. The frontend must reconnect using `Last-Event-ID` where
supported and refresh the affected resource after reconnect.

## Metrics and analytics

Micrometer remains appropriate for runtime health and infrastructure metrics,
but historical frontend dashboards require durable metric facts and read
models. Dashboard endpoints must query tenant-scoped read models rather than
performing expensive raw-event aggregation on every request.

### Dashboard endpoints

```text
GET /api/v1/metrics/overview
GET /api/v1/metrics/bot
GET /api/v1/metrics/leads
GET /api/v1/metrics/conversations
GET /api/v1/metrics/channels
GET /api/v1/metrics/advisors
GET /api/v1/metrics/funnel
GET /api/v1/metrics/timeseries
GET /api/v1/events/audit
```

All endpoints support tenant-scoped filters:

```text
from, to, timezone, branchId, channel, advisorId, groupBy
```

### Bot metrics

- Inbound messages and bot replies.
- Successful completions, provider failures, rules fallbacks, and empty
  responses.
- Handoff recommendation rate, actual handoff rate, and containment rate.
- Average, p50, and p95 classification and generation latency.
- Intent, lead-stage, temperature, handoff, tool-family, and sensitive-data
  distributions.
- Tool invocation count and success/failure by tool family.
- Provider/model usage, token counts when available, and estimated cost.
- Guardrail, self-refinement, and low-confidence outcomes.

### Lead metrics

- Lead count by stage and temperature.
- AI versus human qualification.
- Temperature changes and qualification latency.
- Appointment requests, quotes, CRM sync outcomes, and conversion.
- Handoff rate by lead stage and temperature.
- Funnel progression from contact to qualified, appointment, quote, and deal.

### Conversation and channel metrics

- Active bot, human-pending, human-active, and closed conversations.
- First-response, advisor-response, and resolution latency.
- Messages per conversation and unread/stale conversations.
- Inbound/outbound volume by channel.
- Sent, delivered, read, failed, retried, and dead-lettered messages.
- Meta window status and HSM reopening count.
- Duplicate and malformed webhook deliveries.

### Advisor and executive metrics

- Active advisors and assigned workload.
- Claimed handoffs, response time, resolution time, and resolved conversations.
- Branch comparisons.
- Revenue, margin, closed deals, and conversion rate only when sourced from an
  authoritative CRM or dealership system.

## Durable data model

Existing tables such as `conversation`, `channel_message`, `handoff_ticket`,
`outbox_event`, `audit_event`, `lead`, `lead_score`, and
`conversation_decision` remain the source of domain facts.

Add or evolve the following durable projections:

```text
ai_execution
conversation_metric_fact
lead_metric_fact
delivery_metric_fact
dashboard_metric_daily
```

`ai_execution` stores safe metadata only:

```text
tenant_id
conversation_id
provider
model
classifier_provider
latency_ms
classification_confidence
tool_family
outcome
fallback_used
handoff_recommended
tokens_in
tokens_out
estimated_cost
created_at
```

No raw prompt, completion, phone number, access token, system prompt, or
provider response body is stored in metric facts.

Facts are written transactionally with domain changes where practical and
projected asynchronously for dashboard queries. Projection failures must not
prevent message delivery; they are retried through an internal recovery path.

## Error and privacy behavior

- `401` means missing or invalid authentication.
- `403` means valid authentication without authority or tenant access.
- `404` hides resources outside the tenant boundary.
- `409` means a stale version, lock, or idempotency conflict.
- `422` means a valid request that violates a domain rule, such as an expired
  Meta window.
- `429` means rate limiting.
- `503` means a temporarily unavailable dependency.

Dashboard data is tenant-isolated and permission-scoped. Personal data is
redacted from logs and telemetry. AI diagnostics are disabled for ordinary
advisor roles unless a sanitized diagnostic permission is explicitly granted.

## Implementation sequence

1. Normalize and version the OpenAPI contract and generated DTOs.
2. Add OCI read/query ports and operator application use cases.
3. Add Phase 1 conversation/message/takeover APIs.
4. Add BFF session, DTO mapping, SSE proxy, and contract tests.
5. Add lead/advisor APIs and durable lead metric facts.
6. Add durable AI execution metrics and classification drill-downs.
7. Add delivery/channel/Meta window metrics and HSM operations.
8. Add dealership adapters and CRM synchronization.
9. Add executive, revenue, and margin projections from authoritative CRM data.
10. Add restricted bot configuration, simulation, and test-lab operations.

Each phase requires:

- OpenAPI request/response examples.
- JWT role/scope tests.
- Tenant-isolation tests.
- Idempotency and optimistic-lock tests.
- Persistence and projection tests.
- BFF contract tests.
- SSE reconnect and event-order tests where applicable.

## Success criteria

- The frontend uses only the BFF contract and never fabricates tenant or role
  headers.
- OCI remains the single source of truth for conversations, leads, delivery,
  AI decisions, and assignments.
- Every dashboard number is traceable to tenant-scoped durable facts.
- Bot, lead, channel, advisor, and revenue metrics can be filtered by period,
  branch, channel, and advisor.
- Raw customer/model content is absent from metrics and normal diagnostics.
- Duplicate sends, stale updates, and webhook retries are safe.
- New frontend screens do not require changing OCI domain semantics.
