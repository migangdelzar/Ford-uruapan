# Ford MCP Information Integration Design

**Date:** 2026-09-24  
**Status:** Approved for implementation  
**Scope:** Spring AI client integration for authoritative, volatile Ford information

## Goal

Connect the Ford service to the existing Streamable HTTP MCP server so the assistant can use MCP tools for current vehicle, offer, promotion, provenance, and catalog-status information. The application must fail closed for volatile Ford-information requests when MCP is unavailable; it must not silently answer from a stale PostgreSQL snapshot.

## Existing Context

- Spring Boot 4.1.0, Spring AI 2.0.1, Java 25, synchronous Spring MVC.
- `ChatClient` is already used by `SpringAiChatCompletionAdapter`.
- Local read-only tool callbacks are assembled by `ToolAuthorizationPolicy`.
- Conversation memory is durable in PostgreSQL and hot-cached in Redis.
- The system prompt is runtime-editable through `SystemPromptService`.
- The existing MCP endpoint is Streamable HTTP and is exposed through a configurable tunnel/domain.

## Chosen Architecture

The application will use Spring AI's synchronous MCP client starter and one named Streamable HTTP connection:

```text
ChatClient
  └─ ToolAuthorizationPolicy
      ├─ local read-only tools
      └─ MCP read-only tool callbacks
           └─ Streamable HTTP /mcp
                └─ MCP server
```

MCP callbacks are added to the same policy used by local callbacks. This preserves the existing provider routing, classification-based allow list, system prompt, and conversation-memory flow. No WebFlux or Spring AI streaming is introduced.

### Configuration boundary

Required runtime properties:

```text
FORD_MCP_ENABLED=false
FORD_MCP_URL=http://127.0.0.1:3001
FORD_MCP_ENDPOINT=/mcp
FORD_MCP_TOKEN=
FORD_MCP_REQUEST_TIMEOUT=30s
```

`FORD_MCP_URL` contains only the server base URL. `FORD_MCP_ENDPOINT` contains `/mcp`. The bearer token is injected by a Spring AI MCP client customizer and is never placed in prompts, logs, tool context, or API responses.

### Tool boundary

Only read-only MCP tools are eligible for model calls. The initial allow list is:

- `search_vehicles`
- `get_vehicle`
- `compare_vehicles`
- `search_offers`
- `list_promotions`
- `get_vehicle_provenance`
- `get_catalog_status`

The existing denied-term policy remains active as a defense in depth. MCP tools not in the configured read-only allow list are not exposed to `ChatClient`.

### Source-of-truth policy

- Volatile Ford information: MCP is authoritative.
- PostgreSQL catalog: durable local snapshot and ingestion source, but not an automatic fallback for volatile answers.
- Approved agency knowledge: existing local RAG tool remains available for non-catalog approved information.
- Memory: Redis/PostgreSQL conversation context remains independent of MCP.

When MCP is disabled or unavailable, the model must not receive the volatile MCP tools. The application will add an explicit unavailable-source instruction to the request context for catalog/offer intents, and the assistant will return the existing transparent fallback/handoff response rather than fabricating values. Existing non-volatile assistant behavior remains unchanged.

## Components

1. **Dependency catalog/build**
   - Add `org.springframework.ai:spring-ai-starter-mcp-client` using the existing Spring AI BOM.
   - Do not add the WebFlux MCP starter.

2. **MCP configuration properties**
   - Bind `ford.mcp.enabled`, URL, endpoint, token, timeout, and read-only tool names.
   - Keep disabled by default so local development remains functional without the tunnel.

3. **MCP client composition root**
   - Create the named `ford-uruapan` Streamable HTTP connection.
   - Add the bearer header through the supported Spring AI MCP customizer API.
   - Expose a synchronous MCP callback provider only when configuration is usable.

4. **Tool policy composition**
   - Merge local callbacks and filtered MCP callbacks.
   - Preserve existing classification allow-list behavior and mutation-term filtering.

5. **Volatile-information failure handling**
   - Detect MCP-disabled/unavailable state for catalog and offer intents.
   - Keep the response grounded and transparent; never substitute a stale local snapshot silently.

6. **Configuration and operations**
   - Add safe examples to `.env.example`, `.env.prod.example`, deployment templates, and relevant documentation.
   - Do not commit tunnel tokens or private MCP credentials.

## Request Flow

1. Meta or frontend request enters the existing assistant flow.
2. Durable context is loaded and Redis hot memory is consulted.
3. The existing decision service classifies intent and allowed tool names.
4. `SpringAiChatCompletionAdapter` builds the prompt with the runtime system prompt and memory.
5. For volatile Ford questions, the model receives only the authorized MCP read-only callbacks.
6. Spring AI calls MCP over Streamable HTTP with the configured bearer token.
7. MCP returns current Ford data; the model answers using that result.
8. The assistant response and conversation event are persisted through the existing flow.
9. If MCP is unavailable, the response is transparent and no stale catalog answer is generated.

## Failure and Security Rules

| Scenario | Required behavior |
|---|---|
| MCP disabled | Do not expose MCP callbacks; volatile Ford requests use the transparent unavailable-source path. |
| MCP URL or token missing | Application starts only if MCP is disabled; if enabled, fail configuration validation clearly. |
| MCP connection timeout/error | Do not expose the failure details to the customer; log bounded provider/operation metadata and return the safe fallback. |
| MCP returns no matching data | Tell the customer the data could not be confirmed and offer an advisor. |
| Tool name is not allow-listed | Never expose it to the model. |
| MCP tool attempts mutation | Existing read-only policy rejects it. |
| Token appears in an exception | Redact before logging. |

## Testing Strategy

- Unit test MCP property validation and enabled/disabled behavior.
- Unit test bearer-header customization without using a real token.
- Unit test merging and filtering MCP callbacks in `ToolAuthorizationPolicy`.
- Unit test the fail-closed path for catalog/offer intents.
- Spring context test with MCP disabled to prove existing local startup remains valid.
- Integration test with a fake Streamable HTTP MCP endpoint to verify initialization, authorization header, and tool callback execution.
- Run the existing unit and integration test suites plus a manual health/startup check with MCP disabled and enabled configuration.

## Explicit Non-Goals

- No WebFlux migration.
- No Spring AI streaming response API.
- No MCP server implementation in this repository.
- No replacement of Redis/PostgreSQL conversation memory.
- No automatic stale-PostgreSQL fallback for volatile catalog or offer answers.

