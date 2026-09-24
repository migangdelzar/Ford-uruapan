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
X-Hub-Signature-256: sha256=<HMAC-SHA256(body, app secret)>
Content-Type: application/json
```

For the current single-tenant deployment, the backend assigns the configured
`FORD_META_TENANT_ID` internally. Meta does not need to send a tenant header.

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
- `GET /api/v1/operator/events/stream` (optional `tenantId` override for future multi-tenant mode)

## Runtime AI system prompt

The active prompt is available only to authenticated `OPERATOR` users:

- `GET /api/v1/operator/ai/system-prompt`
- `PUT /api/v1/operator/ai/system-prompt`

Replace the complete prompt document with an optimistic-lock version:

```json
{
  "content": "# IDENTIDAD Y TONO\n...",
  "expectedVersion": 0
}
```

The response includes `content`, `version`, `updatedBy`, and `updatedAt`.
The first successful update changes the version from `0` to `1`; a stale
`expectedVersion` returns `409 Conflict`. The next synchronous Spring AI
completion uses the new prompt without restarting the service. The seed prompt
is stored at `src/main/resources/prompts/ford-uruapan-system.md` and is used
until the first database override exists.

The prompt editor should load the document on screen open, keep the returned
`version` alongside its draft, and send that version on `PUT`. After a `200`,
replace the local draft and version with the response. If the server returns
`409`, keep the unsaved draft, reload the latest document, and ask the operator
whether to merge or discard the conflict. Connected operator screens also
receive `ai.system_prompt.updated` over the existing SSE stream; that event
contains only the new version, so the client should refetch the prompt rather
than expecting prompt content inside SSE.

CRM, email, Slack and WhatsApp notifications are represented by ports/outbox
events so their provider adapters can be added without changing lead scoring or
conversation state.
