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
from its `tenant_id` or `tenant_ids` claim. Current scope requirements are:

- `roles`: one or more of `OPERATOR`, `MANAGER`, or `ADMIN`; these become
  Spring `ROLE_*` authorities and gate the operator API
- `handoff:manage`: create, claim, pause, and resume handoffs
- `operator:read`: list handoffs

The service maps JWT scopes to Spring authorities with the `SCOPE_` prefix,
for example `SCOPE_handoff:manage`. Roles are mapped to `ROLE_*` authorities.
Tenant IDs remain JWT attributes and are checked against the request tenant at
the method-security boundary; they are never accepted from a request header or
query parameter and are not treated as global permissions.

CRM, email, Slack and WhatsApp notifications are represented by ports/outbox
events so their provider adapters can be added without changing lead scoring or
conversation state.
