# Operations

## Local services

`docker-compose.yml` starts PostgreSQL with pgvector and Redis. Liquibase owns
the schema. Do not edit production tables manually; create a new YAML changeSet.

## Configuration

Use environment variables or a deployment profile; keep secrets outside Git.

- `FORD_DATABASE_URL`, `FORD_DATABASE_USERNAME`, `FORD_DATABASE_PASSWORD`
- Production JDBC URLs add `ApplicationName=ford-service`; PostgreSQL logs
  include that application name and SQLSTATE with verbose error detail while
  suppressing SQL text from error logs.
- Metrics and traces push to the in-network OpenTelemetry Collector using
  `FORD_OTEL_METRICS_EXPORT_ENABLED`, `FORD_OTEL_METRICS_ENDPOINT`,
  `FORD_OTEL_TRACES_EXPORT_ENABLED`, `FORD_OTEL_TRACES_ENDPOINT`, and
  `FORD_OTEL_SAMPLING_PROBABILITY`. Prometheus scrapes the Collector exporter,
  not the authenticated `/actuator/prometheus` endpoint.
- `FORD_SECURITY_AUTH_ENABLED`, `FORD_SECURITY_JWT_ENABLED`, `FORD_SECURITY_ISSUER_URI`, `FORD_SECURITY_AUDIENCE`
- `FORD_SECURITY_JWK_PRIVATE_KEY`, `FORD_BFF_OIDC_CLIENT_SECRET`, `FORD_BFF_OIDC_REDIRECT_URI`
- `FORD_RESEND_API_KEY`, `RESEND_FROM` (operator one-time-token email fallback).
  The production release uses `Ford Uruapan <no-reply@emme.business>` by
  default; set the production Actions variable `RESEND_FROM` to override it with
  another sender on the verified `emme.business` domain.
- `FORD_META_APP_SECRET`, `FORD_META_VERIFY_TOKEN`
- `FORD_META_DELIVERY_ENABLED`, channel delivery endpoints and access tokens
- `FORD_META_WHATSAPP_ACCESS_TOKEN` is reused for authenticated WhatsApp Graph
  media lookup/download. Set the non-secret
  `FORD_CHANNELS_META_MEDIA_GRAPH_API_VERSION` to the Graph API version
  supported by the Meta app. The Graph base URL defaults to
  `https://graph.facebook.com`; overrides are accepted only for that official
  HTTPS host.
- `FORD_HUMAN_HANDOFF_ENABLED` (enabled by default; a queued request does not mean an advisor accepted it)
- `FORD_MEMORY_ENABLED`, `FORD_MEMORY_RECENT_MESSAGE_LIMIT`, and
  `FORD_MEMORY_PROMPT_RECENT_MESSAGE_LIMIT` (default `20`). The first setting
  preserves the durable recent-memory bound; the second controls how many
  messages are injected into each model prompt.
- `FORD_MEMORY_SUMMARY_MAX_CHARS`, `FORD_MEMORY_FACT_LIMIT`, `FORD_MEMORY_FACT_MAX_CHARS`
- `FORD_MEMORY_SEMANTIC_ENABLED`, `FORD_MEMORY_SEMANTIC_LIMIT`, `FORD_MEMORY_CACHE_ENABLED`
- `FORD_AI_CHAT_PROVIDER`, `FORD_AI_EMBEDDING_PROVIDER`
- `FORD_AI_CLOUD_ENABLED`, `FORD_AI_LOCAL_ENABLED`, provider base URLs and model names
- `FORD_CONVERSATION_TURN_CONCURRENCY` (start at `1`; raise to `2` then `5` only
  after the corresponding isolation and idempotency gates pass),
  `FORD_CONVERSATION_TURN_QUEUE_CAPACITY` (default `8`), and
  `FORD_CONVERSATION_PUBLICATION_RETRY_AGE` (default `PT1M`). Inbound turns use
  a dedicated bounded executor; Spring Modulith JDBC publications remain the
  durable queue and are retried when worker capacity returns. Watch
  `ford.conversation.worker.active`, `ford.conversation.worker.pool.size`,
  `ford.conversation.worker.queue.size`,
  `ford.conversation.publication.incomplete`, and
  `ford.conversation.publication.oldest.wait.seconds`; advance from one worker
  to two and then five only after tenant isolation, duplicate delivery, and
  optimistic-lock/idempotency gates pass.
- `FORD_AI_FALLBACK_ORDER` (default `cloudflare,groq,gemini,openrouter`) and
  `FORD_AI_PROVIDER_MAX_ATTEMPTS` (default/maximum `5`). Eligible order is
  Cloudflare, Groq, Gemini, OpenRouter, then local Ollama. OpenAI is disabled by
  default. Each eligible provider
  is called once; rate limits, service outages, and network errors can advance
  to the next provider. Retry-After waits are capped by
  `FORD_AI_PROVIDER_MAX_RETRY_AFTER` (default `PT3S`); longer waits are skipped.
- `FORD_AI_GROQ_ENABLED`, `FORD_AI_GEMINI_ENABLED`,
  `FORD_AI_CLOUDFLARE_ENABLED`, and `FORD_AI_OPENROUTER_ENABLED` are all false
  by default. Each also requires its API key and both capability declarations
  (`*_TOOL_CALLING_VERIFIED`, `*_STRUCTURED_OUTPUT_VERIFIED`) before it enters
  the routing chain. Set those declarations only after the provider passes
  the deterministic and provider-specific evaluation cases.
- Provider secrets are `GROQ_API_KEY`, `GEMINI_API_KEY`,
  `CLOUDFLARE_API_TOKEN` plus `CLOUDFLARE_ACCOUNT_ID`, and
  `OPENROUTER_API_KEY`. `openrouter/free` is evaluation-only unless
  `FORD_AI_EVALUATION_MODE=true`; it is not eligible by default.
- The ten Ford lead flows are covered by the deterministic main-app fixture:
  `./gradlew test --tests '*MultistepLeadHandoffFixtureTest'`. It checks only
  application authorization and payload fields; it does not test model quality
  or real provider compatibility. Keep those results separate from the
  historical LLM/Ragas traces in `docs/ford-specialist-handoff.md`. The current
  `mise run test:ai-evaluation --live` path calls OpenRouter only. Do not set a
  provider's capability flags until its own live tool/structured-output eval
  passes; no provider live eval ran as part of this config change.
- For Oracle, `mise run deploy:secrets` adopts exported keys into the ignored
  config, `mise run deploy:check` reports provider readiness without values,
  and `mise run deploy:app` renders the settings to emme-1. Do not put keys in
  Terraform variables or state.
- `FORD_AI_LOG_PROVIDER_ERRORS` (opt-in bounded provider error details)
- `mise run ai:provider-preflight` (opt-in live smoke test for every enabled catalog provider). It loads the ignored deployment config, calls providers through Spring AI with synthetic input and a read-only fake tool, checks conversion into the app's `AssistantGeneration` type, and reports safe provider/model/error details. Set `FORD_AI_PREFLIGHT_PROVIDER_IDS=groq` to probe a subset or `FORD_AI_PREFLIGHT_MODEL_GROQ=<model-id>` to try another model without changing deployment config. It can incur provider charges and never contacts WhatsApp, CRM, or customer data.
- The live preflight prompts for exactly one synthetic tool call and always runs; Gradle must not reuse a prior result because provider models and quotas can change. It uses the same automatic tool choice as production and fails unless the callback runs exactly once. Verified testing models: Cloudflare `@cf/meta/llama-3.3-70b-instruct-fp8-fast`, Groq `openai/gpt-oss-20b`, Gemini `gemini-3.8-flash`, and OpenRouter `openai/gpt-oss-120b`. Groq GPT-OSS also requires `include_reasoning=false` so Groq does not receive unsupported `reasoning_content` on the tool-result turn. Re-run preflight after changing a provider, model, or provider capability flags.
- `FORD_AI_MEDIA_ENABLED` (defaults to `false`), `FORD_AI_MEDIA_APPROVED_PROVIDERS`,
  `FORD_AI_MEDIA_IMAGE_PROVIDERS`, and `FORD_AI_MEDIA_AUDIO_PROVIDERS`. Media is
  sent only to an ID present in the approved and matching modality lists, with
  a healthy configured model that advertises that capability. There is no
  implicit vision/audio support for OpenAI-compatible endpoints such as
  OpenRouter.
- `FORD_AI_MEDIA_MAX_BYTES` (default 10 MiB, hard cap 10 MiB) and
  `FORD_AI_MEDIA_TIMEOUT` (default and maximum 8s, covering download plus
  interpretation). The normal conversation worker deadline still applies.
- `TYPESAFE_API_KEY` (optional Jev classification key; inject only through
  private deployment config; never log or commit it). Workstation operators may
  keep the exported shell value under `FORD_TYPESAFE_JEV_API_KEY`; run
  `mise run deploy:secrets` to copy it into the ignored
  `deployment/config/oci.env`. `mise run deploy:check` reports presence only.
  Ansible then injects the canonical name into emme-1's app environment. If
  missing, startup uses deterministic rules.
- `FORD_AI_CLASSIFICATION_ENABLED`, `FORD_AI_CLASSIFICATION_MODE` (`active`,
  `shadow`, or `disabled`), and `FORD_AI_CLASSIFICATION_PROVIDER`
- `FORD_AI_CLASSIFICATION_MIN_CONFIDENCE`, classifier-specific `FORD_AI_CLASSIFIER_*`
  toggles, and `FORD_AI_CLASSIFICATION_FALLBACK=rules`. Lead/handoff questions
  are enabled; sensitive-data classification is disabled while PII redaction
  and deterministic authorization remain enabled.
- `FORD_AI_GUARDRAILS_ENABLED` and `FORD_AI_SELF_REFINE_ENABLED` (both default
  to `false`); self-refinement is bounded by `FORD_AI_SELF_REFINE_MAX_ATTEMPTS`

For OpenAI-compatible providers, `FORD_AI_CLOUD_BASE_URL` must include the
provider API version because the Spring AI SDK appends `/chat/completions`
directly to it. For example, OpenRouter uses:

```dotenv
FORD_AI_CLOUD_BASE_URL=https://openrouter.ai/api/v1
```

The same shape works for the official OpenAI endpoint:
`https://api.openai.com/v1`.

- `FORD_INGESTION_ENABLED`, `FORD_TENANT_ID`, `FORD_INGESTION_CRON`
- `FORD_CATALOG_ENDPOINT`, `FORD_INVENTORY_ENDPOINT`, `FORD_SCRAPER_ENDPOINT`
- `FORD_MANUAL_CSV_PATH`, `FORD_INGESTION_MAX_ATTEMPTS`

See [integration-setup.md](integration-setup.md) and [.env.example](.env.example)
for the complete local contract. Never commit `.env` or paste provider keys into
source control, tickets, or chat.

The Spring profile is selected with `SPRING_PROFILES_ACTIVE=dev` or
`SPRING_PROFILES_ACTIVE=prod`; environment variables override both profile and
common YAML values. The default profile is `dev`.

API authentication uses Spring Security's OAuth2 Resource Server JWT support.
Set `FORD_SECURITY_JWT_ENABLED=true` only when the configured issuer is
available and publishes OIDC discovery/JWKS metadata. The issuer validates
`iss`, `exp`, and `nbf`; the configured audience validates `aud`. Tenant scope
is carried by `tenant_id` or `tenant_ids` claims and is checked as a request
attribute at the existing tenant authorization boundary. A `roles` claim with
`OPERATOR`, `MANAGER`, or `ADMIN` values becomes `ROLE_*`; operation scopes
remain explicit `SCOPE_*` authorities. Webhooks remain unauthenticated at the
HTTP bearer layer because Meta requests are authenticated with HMAC,
verify-token, and channel-binding checks.

### Meta messaging channels

For a single Meta app, register one callback URL per product at
`/webhooks/meta/whatsapp`, `/webhooks/meta/messenger`, and
`/webhooks/meta/instagram`. Verification uses the configured
`FORD_META_VERIFY_TOKEN`; POSTs are checked with `FORD_META_APP_SECRET` before
their body is parsed. All channels share the inbound pipeline, while account
bindings map the signed provider account ID to the tenant. Do not put a tenant
ID in the URL or trust a tenant header.

If the deployment later hosts multiple Meta apps/integrations, use an explicit
integration key such as `/webhooks/meta/{channel}/{integrationKey}` only to
select that integration's app secret and verify token. Keep provider-account to
tenant binding as a separate lookup. With the current single-app configuration,
adding a `/ford` path segment would not add isolation because there is only one
app-secret/verify-token configuration.

WhatsApp uses `FORD_META_PHONE_NUMBER_ID` for binding and delivery. It falls back
to `FORD_META_PROVIDER_ACCOUNT_ID` and then `FORD_META_PHONE_ID` for older
deployments. Keep `FORD_META_WHATSAPP_BUSINESS_ACCOUNT_ID` (or legacy
`FORD_META_WABA_ID`) separate: the WABA ID is not the inbound binding key or
the Graph `/messages` endpoint ID. `FORD_META_PHONE_NUMBER` is a human-readable
reference only. Optional
startup bindings for Messenger and Instagram use
`FORD_META_MESSENGER_AUTO_BINDING_ENABLED` + `FORD_META_MESSENGER_PAGE_ID` and
`FORD_META_INSTAGRAM_AUTO_BINDING_ENABLED` + `FORD_META_INSTAGRAM_ACCOUNT_ID`.
All use `FORD_TENANT_ID`. Leave auto-binding disabled when managing bindings
explicitly or when an integration serves multiple provider accounts.

Outbound delivery is separately opt-in with `FORD_META_DELIVERY_ENABLED` and
the channel-specific endpoint/access-token pairs:
`FORD_META_WHATSAPP_DELIVERY_ENDPOINT` + `FORD_META_WHATSAPP_ACCESS_TOKEN`,
`FORD_META_MESSENGER_DELIVERY_ENDPOINT` + `FORD_META_MESSENGER_ACCESS_TOKEN`,
and `FORD_META_INSTAGRAM_DELIVERY_ENDPOINT` + `FORD_META_INSTAGRAM_ACCESS_TOKEN`.
The endpoint/API version and credentials must match the corresponding Meta
product and account. Only text delivery is implemented for Messenger and
Instagram; their inbound media is acknowledged as unsupported without storing
or forwarding provider media URLs. WhatsApp's existing typed-message handling
is unchanged.

For development diagnostics, set `FORD_META_DIAGNOSTIC_STACKTRACES_ENABLED=true`
to emit a synthetic stack trace for outbound Meta API rejections. Rejection logs
include HTTP status and allowlisted Meta error fields (`type`, `code`,
`error_subcode`, `fbtrace_id`); provider messages, message bodies, recipients,
access tokens, and query strings are not logged.

The outbox checks the persisted conversation's 24-hour customer-service window
before calling a configured provider port. Missing, mismatched, or expired
windows are permanent delivery rejections; this service does not automatically
send templates or initiate out-of-window messages. Provider credentials,
permissions, app review, webhook subscriptions, and account linking must be
completed in Meta's console before live delivery can be validated.
At minimum, WhatsApp Cloud API messaging requires `whatsapp_business_messaging`,
Messenger Send API requires `pages_messaging`, and Instagram messaging requires
the messaging permission for the selected Instagram API login mode
(`instagram_business_manage_messages` for Instagram Login; Facebook Login uses
its corresponding Instagram messaging permission). Meta App Review/Advanced
Access and account-role prerequisites depend on whether the app is in testing
or production. Verify the current requirements in the [WhatsApp Cloud API
collection](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api),
[Messenger Send API](https://www.postman.com/meta/messenger-platform-api/folder/vilwbh4/send-api),
and [Instagram API documentation](https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api)
before production setup.

The current operator scopes are:

- `operator:read` for read-only operator endpoints
- `handoff:manage` for handoff commands

Spring Security 7 method security is enabled with `@EnableMethodSecurity`, and
endpoint-specific rules use `@PreAuthorize`. This is the current
AuthorizationManager-based replacement for the deprecated
`@EnableGlobalMethodSecurity`; `@PreAuthorize` itself remains supported.

Bot-only mode is controlled by:

```dotenv
FORD_HUMAN_HANDOFF_ENABLED=false
```

This disables internal handoff queue creation. The workflow continues with a
truthful bot response and does not claim an advisor was contacted. Set it back
to `true` to restore authorized handoff behavior.

## Conversation graph and sales escalation

`FORD_CONVERSATION_GRAPH_ENABLED` selects the bounded LangGraph4j inbound
workflow. It defaults to `true` and is the canonical active path. The graph runs one
accepted inbound turn at a time and invokes the existing Spring AI assistant
once; the model, authorized Ford MCP/RAG tools, conversation transcript, lead
journey, and outbox remain owned by their existing application services and
PostgreSQL tables. Graph checkpoints live in the separate `ford_graph` schema
and contain only tenant, conversation, and receipt identifiers plus route
metadata. They are execution checkpoints, not conversation memory or the
system of record. Disable the flag and redeploy to return to the legacy
workflow; do not drop checkpoint or business tables as part of rollback.

With `FORD_HUMAN_HANDOFF_ENABLED=true`, a direct customer request can enter the
internal `/api/v1/handoffs` queue. A HOT lead receives an offer; HOT status alone
never creates a queue item. An affirmative reply to the persisted offer creates
one internal request. A decline suppresses later proactive offers, while a
later direct request remains valid. A queued ticket means the request is waiting;
only a successful operator claim means an advisor has taken the conversation,
at which point automated replies pause. Operators need
`operator:read` to list tickets and `handoff:manage` to claim, pause, resume, or
hand back a conversation. Claims are tenant-scoped and version-checked.

For WARM leads, the assistant may offer an optional advisor follow-up. A
follow-up is queued only after an unambiguous customer acceptance; declining
suppresses another offer for that conversation. WARM follow-ups are listed at
`GET /api/v1/follow-ups` and claimed at
`POST /api/v1/follow-ups/{followUpId}/claim?tenantId=...`. Claiming a WARM
follow-up does not pause the bot or claim that an advisor has joined the live
conversation.

For the OCI rollout, use `mise run deploy:flags -- FORD_CONVERSATION_GRAPH_ENABLED
true FORD_HUMAN_HANDOFF_ENABLED true` to set only these allowlisted booleans in
the private deployment config, then deploy through `mise run deploy:app`.
Before a release, inspect both hosts and logs, run `mise run backup:run` and
`mise run backup:verify`, and save the current app image with
`mise run deploy:image-backup -- <current-image>`. Publish and deploy an
immutable GHCR tag. If startup, migration, or health checks fail, deploy the
previous immutable tag through the same app task. Migrations 013 and 014 are
additive; never reset the production database or tenant data for rollback.

Conversation memory is enabled by default and is persisted in PostgreSQL. The
session scope is tenant, channel, provider account, and participant, so the
same person on two provider numbers cannot share memory accidentally. Every
accepted inbound message and successful bot reply is written to the canonical
`conversation_message` transcript. Redis is only a 24-hour cache and is not
the source of truth; the working-memory projection expires after 90 days of
inactivity.

Each model request receives a controlled context: the compact summary, up to
`FORD_MEMORY_PROMPT_RECENT_MESSAGE_LIMIT` recent messages (20 by default), and
durable facts. When the recent window rolls over, the provider chain compacts
expired messages into a bounded summary. The database transcript is never
deleted. PII redaction and prompt-injection checks run before compaction;
empty or oversized summaries are rejected. If compaction fails, the last valid
summary is retained and the full transcript remains durable, though expired
turns may not be present in that request's prompt. Projection version checks
prevent stale compaction results from replacing newer state.

Semantic retrieval is disabled by default. The current pgvector adapter
retrieves semantic facts, not archived message turns; enabling it does not yet
provide full event-stream recall. A future message-recall adapter should index
redacted transcript turns and retrieve only the top 2-3 relevant turns under a
token budget. Memory read, embedding, and provider failures are fail-open for
webhook processing.

Approved knowledge retrieval combines Spring AI pgvector similarity with
PostgreSQL full-text ranking over the same `public.vector_store` rows. Both
paths apply tenant, locale, document type, and approval constraints before
fusion; reciprocal-rank fusion combines the top 20 candidates from each path
and returns at most 8 chunks. This applies to approved knowledge only; vehicle
catalog matching remains structured and exact. It does not index archived chat
messages or require a graph database. The keyword path currently expects the
Spring AI default table name and schema (`public.vector_store`). PostgreSQL
full-text currently evaluates an unindexed expression; add a GIN expression
index after validating the expected knowledge corpus size and deployment
migration procedure.

Jev classification is a separate structured decision step, not the response
generation model. When enabled with a real `TYPESAFE_API_KEY`, it classifies
intent, lead stage/temperature, handoff signals, and tool family. Sensitive-data
classification is disabled; the application still redacts recognized PII and
keeps it out of remote Jev classification. The application passes only a
sanitized decision context to generation,
keeps authorization and handoff policy in Java, and persists normalized
evidence in `conversation_decision`. If Jev is unavailable, malformed, or
below the configured confidence floor, deterministic rules continue the turn.
A confident Jev lead stage/temperature is not replaced by a second deterministic
score. Jev handoff output is advisory; Java enforces direct requests or clear
consent after an offer.
Use `FORD_AI_CLASSIFICATION_MODE=shadow` to exercise the provider while
retaining rules decisions.

Lead follow-up keeps an explicit pending question. A clear affirmative after a
single-option test-drive offer is handled locally: the app records the
`TEST_DRIVE_APPOINTMENT` topic and asks for one preferred day. Ambiguous
multi-option offers do not auto-select an appointment. This is interest capture;
it does not book a slot or claim advisor confirmation. Generated replies asking
multiple questions or combining multiple requested details get one repair
attempt through `AssistantResponseQualityPolicy`.

Jev's published rate is $0.042 per million input tokens; output tokens are
free. The app makes a classification request per eligible customer turn, so
conversation and memory context affect usage. The TypeSafe API key is never
printed by deployment preflight. Set a $5 account-level usage cap in the
TypeSafe console. If the account uses purchased credits without a monthly cap,
disable automatic refills and keep the purchased balance within that limit so
exhausting credits stops paid requests. Confirm existing balance and refill
settings before production activation. See [TypeSafe model pricing](https://docs.typesafe.ai/models)
and [TypeSafe credit terms](https://typesafe.ai/legal/mca).

The initial 8-case Spanish smoke evaluation uses synthetic dealership messages
only and is recorded in [the Jev evaluation note](../../docs/jev-spanish-smoke-eval.md).
It is a routing check, not a broad accuracy benchmark.

The input security policy runs before Jev and response generation. It blocks
common direct attempts to override or reveal system instructions, redacts
recognized email, Mexican phone, payment-card, credential, CURP/RFC/CLABE
values from the current request and model context by default, and marks such
turns sensitive. Sensitive turns skip remote Jev classification and cloud model routing; they use
the local model only when it is available. `FORD_AI_CLASSIFICATION_PII_POLICY` accepts
`redact` (default), `block` (return a safe request to remove the data), or
`allow` (preserve the text but keep it out of remote classifiers and cloud chat
providers). Recognized injection text inside historical memory or journey
context causes that context block to be omitted. These deterministic patterns
are defense in depth, not a complete prompt-injection detector; keep scope and
tool authorization enforced in Java and extend the synthetic adversarial evals.

Output repetition remains handled by `AssistantResponseQualityPolicy`: it
rejects repeated answers, requests one repair attempt, then returns a safe
fallback if the repaired answer still fails. The similarity check is
heuristic; it does not guarantee detection of every paraphrase.

The optional Jev self-refine advisor evaluates groundedness and safety and may
retry a generated answer up to the configured bounded attempt count. The Jev
guardrail advisor is the final safety layer when enabled. Both are disabled by
default because they add latency and provider calls; enable them only after
classification and generation have been verified.

To inspect persisted classifier evidence:

```sql
select classifier_type, classification, confidence, fallback_used, created_at
from conversation_decision
where conversation_id = '<conversation-id>'
order by created_at desc;
```

The default ingestion flag is disabled. Enable it only after official Ford
endpoints or authorized scraper endpoints and credentials are configured.

## mise environments and task groups

`ford-ai-service/.miserc.toml` selects `dev` by default, so local commands do
not need an environment flag. Use `mise -E prod ...` only when validating the
production overlay.

```bash
mise run env:check
mise run env:list
mise run docker:deps:up
mise run docker:deps:ps
mise run docker:deps:down
mise run db:reset -- --confirm
mise run db:reset-volume -- --confirm
mise run app:run
mise run docker:up
mise run docker:ps
mise run observability:up
mise run observability:logs
```

`mise run test:unit` is the non-integration verification command for the
application configuration. The separate `mise run test:integration` task uses
Testcontainers and is not part of this verification.

Use the host-JVM mode when only PostgreSQL and Redis should run in containers:

```bash
mise run docker:deps:up
mise run app:run
```

Use the full container mode when PostgreSQL, Redis, and Spring Boot should all
run in Compose:

```bash
mise run docker:up
```

Host-JVM observability uses host loopback addresses. Keep these values in the
private `.env` used by `mise run app:run`:

```dotenv
FORD_OTEL_TRACES_EXPORT_ENABLED=false
FORD_AI_LOCAL_BASE_URL=http://127.0.0.1:11434
FORD_OTEL_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces
```

Compose uses the separate `*_DOCKER` values from `.env.example`, so
`host.docker.internal` is not used by a JVM running directly on Linux. Trace
export is disabled by default. If you want observability, set
`FORD_OTEL_TRACES_EXPORT_ENABLED=true`, start the local collector, and then
start the application:

```bash
mise run observability:up
mise run app:run
```

The HTTP exchange filter logs request arrival and completion with method, path,
status, duration, content types, and `X-Request-Id`. It intentionally does not
log query values, request/response bodies, authorization headers, cookies, or
Meta signatures.

### WhatsApp typed messages and media

Each message in a Meta webhook is normalized and accepted independently using
the provider message ID for deduplication. Existing `channel_message` rows keep
their content; Liquibase migration 018 adds `message_kind` with a `TEXT` default
and empty JSON attachment metadata. Attachment metadata contains only the
provider's opaque media ID, declared MIME type, filename, and declared size—no
download URL, access token, or media bytes.

Text, interactive choices, and shared locations enter the existing classifier
and assistant flow as normalized text. Images and audio stay disabled unless
the media feature and both provider allowlists are explicitly configured. The
Meta token and Graph API version must also be present. Downloads are tied to an
accepted tenant message, restricted to HTTPS Meta media hosts, size/MIME
checked, held temporarily in memory, and cleared after interpretation. Documents,
video, stickers, unsupported MIME types, missing media, provider failures, and
timeouts produce a concise Spanish request for a text description; no answer
model call is made for a failed-media turn.

Before enabling media, verify the actual model/endpoint supports the modality,
obtain approval to send raw customer media to that provider, and add its exact
provider ID to the approved and modality allowlists. Audio additionally needs a
Spring AI `TranscriptionModel` bean for the configured cloud provider. Never
log media URLs, tokens, bytes, or raw media; only successfully resolved
text/description is written to the canonical transcript.

`docker:deps:down` stops only PostgreSQL and Redis and preserves the named
PostgreSQL volume.

For a clean local database, stop the application first. The schema reset keeps
the named volume but deletes all application tables and data; Liquibase
recreates them when the application starts:

```bash
mise run db:reset -- --confirm
mise run app:run
```

The full reset deletes this Compose project's PostgreSQL volumes, recreates
PostgreSQL and Redis, and also requires explicit confirmation:

```bash
mise run db:reset-volume -- --confirm
mise run app:run
```

Both resets delete channel bindings. The default `dev` profile recreates the
single configured WhatsApp binding automatically when `FORD_META_PHONE_ID` is a
numeric phone-number ID. Set `FORD_META_AUTO_BINDING_ENABLED=false` when
managing bindings manually or when using multiple numbers; use
`mise exec -- ./bin/create-meta-binding.sh <PHONE_NUMBER_ID>` for those rows.

Production requires database variables from the caller, CI, or a secret
manager:

```bash
mise -E prod run env:check
mise -E prod run docker:config
```

## Fresh-host setup

Install mise once with the official installer, then run setup from this
directory:

```bash
curl -fsSL https://mise.run | sh
mise trust
mise run setup:check
mise run setup:host
mise run setup:host -- --apply
mise run setup:docker -- --apply
mise run setup:env
```

`setup:host` is a dry-run unless `--apply` is supplied. Fedora and Oracle
Linux use Docker’s RPM repository through `dnf`, install rootless prerequisites,
and configure a per-user Docker systemd service. They do not use the Docker
group. On Fedora and Oracle Linux, `newuidmap` and `newgidmap` come from
`shadow-utils`. Rootless Docker requires those commands and at least 65,536
subordinate UIDs and GIDs in `/etc/subuid` and `/etc/subgid`.

macOS uses Homebrew and the `emme` Colima profile. Colima provides the Docker
Linux VM; its Docker daemon runs as root inside that VM, while the macOS host
does not run a Docker daemon. The setup preserves existing profile data and
does not delete or recreate profiles automatically.

`setup:check` reports the active Docker context and socket. The runtime helper
passes the user-scoped rootless socket to Gradle/Testcontainers on Linux and
the Colima socket plus in-VM override on macOS. If a rootful Docker service is
already active on Linux, setup stops with migration instructions instead of
disabling or deleting it.

`setup:env` copies `.env.example` to a private `.env` only when `.env` is
absent. It never overwrites an existing file or creates production env files.

`.env` is ignored and is used only for local development. An optional ignored
`mise.prod.local.toml` may load `.env.prod` for manual production-like testing;
it must not be used as the production secret source. The local override has
this shape:

```toml
[env]
_.file = { path = ".env.prod", redact = true }
```

## Health and metrics

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`
- `GET /actuator/modulith` (operator role required)

Metrics use bounded provider, tool and lead-band labels. Prompts, completions,
raw webhook bodies, phone numbers, email addresses and financial details must
not be placed in logs or metric labels. Use `DefaultPiiRedactor` before any
diagnostic payload is exported.

### Chat failure triage and release gate

Run these gates from `ford-ai-service` before publishing an application image:

```bash
mise run test:unit
mise run test:integration
mise run test:ai-evaluation
mise run test:check
```

The integration gate requires Docker/Testcontainers. The AI evaluation task
runs deterministic Java and Node fixture tests without contacting OpenRouter;
`mise run test:ai-evaluation --live` is a separate, opt-in model call and must
not use customer conversation data. A `202` response from a webhook means the
inbound event was accepted, not that an assistant reply was delivered.

Use the durable stores to distinguish internal memory refresh from external
delivery. These aggregate queries deliberately omit event payloads, transcript
content, tenant identifiers, and serialized Modulith events:

```sql
select event_type, count(*) as outstanding,
       min(publication_date) as oldest_publication
from event_publication
where completion_date is null
group by event_type
order by oldest_publication;

select status, attempts, count(*) as events,
       min(available_at) as oldest_available
from outbox_event
where status in ('PENDING', 'PROCESSING', 'DEAD_LETTER')
group by status, attempts
order by status, attempts;
```

An outstanding `ConversationMemoryProjectionRefreshRequested` publication
means the derived memory projection needs retry; the accepted transcript and
outbound event are committed independently. A growing `outbox_event` retry
count means customer delivery is delayed; `DEAD_LETTER` needs operator review
before any replay. Never replay an outbound message solely because a projection
failed.

During an incident, check `/actuator/health`, then the bounded observations and
the two aggregate queries. Provider `rate_limited`/`provider_unavailable`
events retry at most once per eligible provider before policy-approved fallback;
repeated failures indicate upstream/provider capacity or configuration, not a
reason to increase retry loops. Projection `persistence` failures are recoverable
through Modulith publications. Outbox `retry` followed by `dead_letter` indicates
delivery exhaustion; inspect the redacted error category and provider health.
Review `mise run deploy:logs` through approved redacted filters only. Do not
paste raw logs, SQL payload columns, prompts, tool results, phone numbers, tokens,
or provider response bodies into incident notes.

For a production release, additionally validate `mise run deploy:check` and
`mise run deploy:status`, record the immutable current app image for rollback,
then publish/deploy only after explicit release approval. The app deploy targets
`emme-1`; database and Redis services remain on `emme-2`. Verify health and
redacted startup/provider/MCP summaries after rollout. Do not reset data volumes
or send a customer test message as part of release verification.

## Provider and safety controls

Cloud/local model choice is policy-driven and never silently falls back across
privacy boundaries. Sensitive requests stay local when a local provider is
enabled. Models receive read-only tools only; finance calculations and lead
scores remain deterministic.

To disable AI while keeping webhook acknowledgement and operator access,
disable both AI provider flags and route the conversation to the human queue.
To disable ingestion, set `FORD_INGESTION_ENABLED=false` and leave canonical
data available with its freshness status.

Ingestion runs only from the scheduled coordinator. Accepted catalog records
are idempotently upserted into `vehicle_model`, `vehicle_variant`, and
`vehicle_price`; inventory records upsert `vehicle_unit`. Every write carries
the tenant, source identity, freshness status, raw source JSON, and sync-run
identity. Apply schema changes through Liquibase only.

For local Testcontainers failures on Colima, use the persisted `emme` profile
and run `ford-colima-recover`; it restores the Docker context and starts the
profile before tests. The Gradle test tasks also set
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, preventing Ryuk
from attempting to mount the macOS socket path inside the Colima VM.

## Rollback

Deploy the previous application image, preserve the database volume, and do not
roll back Liquibase changes independently of the application version. Replay
pending outbox events only after verifying provider idempotency keys.
