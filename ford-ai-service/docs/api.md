# API and webhook contracts

All monetary values are MXN. Finance estimates are explicitly non-binding and
must not be presented as a credit decision.

## Meta webhooks

Configure `FORD_META_VERIFY_TOKEN` and `FORD_META_APP_SECRET`. Meta subscription
verification is:

```text
GET /webhooks/meta/{whatsapp|messenger|instagram}?hub.mode=subscribe&hub.verify_token=...&hub.challenge=...
```

Inbound delivery is:

```text
POST /webhooks/meta/{channel}
X-Tenant-Id: <tenant UUID>
X-Hub-Signature-256: sha256=<HMAC-SHA256(body, app secret)>
Content-Type: application/json
```

The endpoint returns `202` for a valid new or duplicate delivery, `403` for an
invalid signature, and `400` for malformed/non-text payloads. Provider message
IDs are deduplicated before application processing.

## Operator inbox

Operator endpoints require an authenticated user with role `OPERATOR`:

- `POST /api/operator/handoffs`
- `POST /api/operator/handoffs/{ticketId}/claim?operatorId=...`
- `POST /api/operator/handoffs/conversations/{conversationId}/pause?operatorId=...`
- `POST /api/operator/handoffs/conversations/{conversationId}/resume?operatorId=...`
- `GET /api/operator/handoffs?tenantId=...`

CRM, email, Slack and WhatsApp notifications are represented by ports/outbox
events so their provider adapters can be added without changing lead scoring or
conversation state.
