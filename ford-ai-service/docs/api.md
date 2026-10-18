# API and webhook contracts

All monetary values are MXN. Finance estimates are explicitly non-binding and
must not be presented as a credit decision.

The machine-readable frontend contract is
[`openapi/frontend-api.yaml`](openapi/frontend-api.yaml). Authentication and
JWT acquisition are documented in
[`openapi/security.md`](openapi/security.md). The OpenAPI document marks each
operation as `implemented` or `planned`.

## Meta webhooks

Configure `FORD_META_VERIFY_TOKEN` and `FORD_META_APP_SECRET`. Meta subscription
verification is:

```text
GET /webhooks/meta/{whatsapp|messenger|instagram}?hub.mode=subscribe&hub.verify_token=...&hub.challenge=...
```

Inbound delivery is:

```text
POST /webhooks/meta/{channel}/{webhookKey}
X-Hub-Signature-256: sha256=<HMAC-SHA256(body, app secret)>
Content-Type: application/json
```

The opaque `webhookKey` resolves the enabled tenant/channel binding. Tenant
identity is never accepted from a request header. The endpoint returns `202`
for a valid new or duplicate delivery, `403` for an invalid signature, and
`400` for malformed/non-text payloads. Provider message IDs are deduplicated
before application processing.

## Operator inbox

Operator endpoints require a bearer JWT issued by the configured OIDC issuer.
The token audience must include `ford-ai-service`, and tenant access is read
from its `tenant_id` or `tenant_ids` claim. When an endpoint accepts an
optional `tenantId` selector, it must match one of those claims; it is never
trusted as authorization by itself. Current scope requirements are:

- `roles`: one or more of `OPERATOR`, `MANAGER`, or `ADMIN`; these become
  Spring `ROLE_*` authorities and gate the operator API
- `handoff:manage`: create, claim, pause, and resume handoffs
- `operator:read`: list handoffs
- `operator:read`: list unsent shadow drafts for a conversation
- `operator:write`: accept or reject a shadow draft for evaluation; this never sends it

The service maps JWT scopes to Spring authorities with the `SCOPE_` prefix,
for example `SCOPE_handoff:manage`. Roles are mapped to `ROLE_*` authorities.
Tenant IDs remain JWT attributes and are checked against any requested tenant;
they are not accepted from request headers or treated as global permissions.

The current Ford Service repository intentionally exposes an API-only operator
inbox. Shadow evaluation uses `GET /api/v1/conversations/{conversationId}/shadow-drafts`
and `POST /api/v1/conversations/{conversationId}/shadow-drafts/{draftId}/review`;
the latter only records an annotation and cannot trigger delivery. No React
inbox exists in this repository, so the service contract is ready for the
existing operator client when that separate frontend is available.

CRM, email, Slack and WhatsApp notifications are represented by ports/outbox
events so their provider adapters can be added without changing lead scoring or
conversation state.
