# Frontend system prompt flow

The CRM prompt editor should treat the backend as the source of truth. The
editor is not a local configuration form and should not send the prompt to
Spring AI directly.

## Screen lifecycle

1. When the “Reglas y Tono” screen opens, call
   `GET /api/v1/operator/ai/system-prompt` with the authenticated operator
   session.
2. Put `content` into the editor and keep `version` in component state. Show
   the version and `updatedAt` as secondary metadata.
3. Keep a dirty flag by comparing the draft with the last server response.
   Warn before navigation when a dirty draft would be lost.
4. Disable save while the request is pending. Show “Guardando…” and preserve
   the draft if the request fails.

## Save lifecycle

Send a full-document replacement:

```http
PUT /api/v1/operator/ai/system-prompt
Content-Type: application/json

{"content":"<draft>","expectedVersion":3}
```

On `200`, replace both the editor value and local version with the response,
clear the dirty flag, and show a short confirmation. The change is used by the
next synchronous AI completion; no model restart is needed.

On `409`, do not overwrite the draft. Refetch the current prompt, show the
server version beside the local draft, and offer “Recargar servidor” or “Copiar
mi borrador”. This is the optimistic-lock protection for two operators editing
at the same time.

On `401/403`, send the operator to login or show an authorization message. On
`400`, keep the draft and show the validation error. Never put the prompt in a
URL, analytics event, toast, or browser notification.

## Live synchronization

Keep one authenticated connection to:

```text
GET /api/v1/operator/events/stream
```

The prompt update event is named `ai.system_prompt.updated` and contains only
metadata such as the new version. When another tab receives it, refetch the
prompt if the current editor is clean. If the editor is dirty, show a small
“Hay una versión nueva” banner and let the operator compare before replacing
the draft. Existing `message.created` and handoff events continue through the
same stream.

SSE is the right foreground mechanism. It is not a durable queue: after a
reconnect, refetch the relevant state. For background notifications, add PWA
service-worker Web Push later; request notification permission only after an
explicit operator action. Web Push complements SSE and should not replace it.

## UI boundaries

The mode cards shown in the prototype (“Piloto Automático”, “Copiloto
Asistido”, and “Atención Manual”) should remain presentation-only until their
backend policy endpoints exist. The prompt editor can be shipped now because
its versioned `GET/PUT` contract and SSE refresh event are implemented.

## Ford information tool boundary

The assistant is scoped to Ford vehicle shopping. Current vehicle, offer, and
catalog-status facts are supplied by the backend's authenticated MCP client;
the frontend never calls MCP directly and never receives the MCP bearer token.
The backend exposes only read-only tools such as `search_vehicles`,
`get_vehicle`, `compare_vehicles`, `search_offers`, `list_promotions`,
`get_vehicle_provenance`, and `get_catalog_status`.

Financing and appointments may be explained informationally, but purchases,
payments, bookings, outbound messages, handoffs, and data mutations are not
agent capabilities. Unrelated requests are stopped by the backend scope guard
before model execution.
