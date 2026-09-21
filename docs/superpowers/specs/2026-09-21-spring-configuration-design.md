# Spring configuration and capability wiring design

## Goal

Make the Ford service configuration deterministic across development and
production, keep optional capabilities independently switchable, and make
bot-only operation explicit. The refactor covers AI, conversations, Meta
channels, outbox, ingestion, and observability without changing the service's
public webhook contract or requiring a database migration.

The implementation must use unit and Spring slice tests only. Integration tests
are explicitly out of scope.

## Configuration architecture

The application keeps Spring Boot component scanning, while each bounded
context owns an explicit composition configuration:

| Module | Configuration responsibility |
| --- | --- |
| AI | typed AI properties, provider policy, completion port, orchestration, assistant service |
| Conversations | assistant adapter, handoff policy, conversation workflow |
| Meta channels | typed webhook and delivery properties, channel adapters, webhook routing |
| Outbox | publisher, delivery job, recovery job, retry settings |
| Ingestion | typed ingestion properties and scheduled job |
| Observability | request logging, tracing, metrics, and safe AI diagnostics |

Configuration classes use `proxyBeanMethods = false`. Bean methods are kept
focused and package-private where the module permits it.

Regular application configuration does not use order-sensitive
`@ConditionalOnBean` checks. `@ConditionalOnProperty` is used for actual
feature switches, and `ObjectProvider` is used only for optional capabilities
such as catalog, finance, and knowledge tools.

The core conversation path always has a complete bean graph:

```text
typed properties
      |
      +-- provider policy -- provider adapters or unavailable-provider adapter
                                  |
                           chat completion port
                                  |
                           assistant orchestrator
                                  |
                           assistant service
                                  |
                           conversation assistant
                                  |
                           conversation workflow
```

`AssistantService` and `ChatCompletionPort` always have one application bean.
When no usable AI provider is enabled, the completion path returns a controlled
unavailable-provider result instead of causing the conversation configuration
to disappear.

The Meta module uses Spring Boot's managed `ObjectMapper`; it does not replace
global Jackson configuration with a module-local mapper.

## Property and environment contract

`application.yml` contains common defaults. Profile overlays are:

- `application-dev.yml` for local development with safe local defaults;
- `application-prod.yml` for production-like behavior without secret values.

`SPRING_PROFILES_ACTIVE=dev|prod` selects the overlay. Environment variables
provided by mise, Docker Compose, OCI, or a secret manager override profile
files. Secrets are never committed to profile files or examples.

The existing `FORD_*` environment names remain supported. The application
binds them into typed properties under these namespaces:

```text
ford.ai.*
ford.conversations.*
ford.channels.meta.*
ford.outbox.*
ford.ingestion.*
ford.observability.*
```

The bot-only switch has the exact Spring property mapping
`FORD_HUMAN_HANDOFF_ENABLED` -> `ford.conversations.human-handoff.enabled`.

The principal switches are:

```text
FORD_HUMAN_HANDOFF_ENABLED=true|false
FORD_AI_CLOUD_ENABLED=true|false
FORD_AI_LOCAL_ENABLED=true|false
FORD_AI_CHAT_PROVIDER=...
FORD_META_DELIVERY_ENABLED=true|false
FORD_OUTBOX_ENABLED=true|false
FORD_INGESTION_ENABLED=true|false
FORD_OTEL_TRACES_EXPORT_ENABLED=true|false
FORD_OTEL_METRICS_EXPORT_ENABLED=true|false
```

Human handoff defaults to enabled in the application contract for production
safety. Bot-only operation is enabled explicitly with:

```bash
FORD_HUMAN_HANDOFF_ENABLED=false
```

AI provider selection is deterministic:

- cloud only selects the configured cloud provider;
- local only selects the configured local provider;
- both use the existing provider policy, with sensitive requests restricted
  from cloud providers;
- neither starts successfully and returns the unavailable-provider fallback;
- missing credentials or unusable provider combinations are reported by
  diagnostics without preventing webhook verification.

## Human handoff behavior

The handoff setting is enforced at the conversation workflow boundary so no
caller can accidentally create a handoff side effect when bot-only mode is
enabled.

```text
inbound event
   |
   +-- conversation is not bot-active -> ignore
   |
   +-- normal assistant response -> persist turn -> publish outbound request
   |
   +-- handoff requested
          +-- enabled  -> persist handoff -> publish HandoffRequested
          +-- disabled -> persist bot fallback -> publish outbound request
```

When `FORD_HUMAN_HANDOFF_ENABLED=false`:

- an AI response requesting handoff is converted to a normal bot fallback;
- provider failures do not create `handoff_ticket` rows;
- `HandoffRequested` is not published;
- no human notification is triggered by an assistant response;
- existing human-state conversations are not silently rewritten, but new
  handoff side effects are blocked by the policy.

When enabled, the existing handoff behavior remains unchanged.

## Failure handling and diagnostics

Configuration failures are classified separately from runtime failures.

Required production infrastructure fails clearly. Optional AI, outbox,
ingestion, observability, and delivery capabilities remain disabled or use a
deterministic fallback when not configured.

AI runtime failures never escape after an inbound event has been accepted. The
assistant returns a deterministic fallback. Handoff-enabled mode may mark that
fallback for human escalation; bot-only mode treats it as a normal bot turn.

Safe diagnostics include provider, model, operation, exception type, request
ID, and conversation ID where safe. They do not include prompts, completions,
customer message content, access tokens, app secrets, authorization headers,
or credential-bearing URLs.

Startup diagnostics emit a redacted capability summary, for example:

```text
profile=dev
ai.cloud=enabled
ai.local=disabled
ai.provider=openai
humanHandoff=disabled
meta.inbound=enabled
meta.outbound=disabled
outbox=disabled
observability.traces=disabled
```

The HTTP exchange filter remains responsible for request-level method, path,
status, duration, and request ID logging. AI diagnostics remain at the
application boundary.

Outbox and external delivery configuration must be diagnosed as a pair. An
enabled outbox without a delivery route, or enabled external delivery without
the outbox path, is reported as a configuration problem rather than appearing
healthy.

## Testing strategy

Only unit and Spring slice tests are added or changed.

Unit coverage includes:

- provider selection for cloud-only, local-only, both-enabled, sensitive-data,
  and no-provider cases;
- provider failure with handoff enabled and disabled;
- an explicit assistant handoff with handoff enabled and disabled;
- missing optional tools and optional assistant capabilities;
- invalid configuration combinations and deterministic fallback metadata.

Spring slice coverage includes:

- deterministic registration of the AI and conversation bean graph;
- fallback assistant availability with no usable provider;
- absence of ingestion and outbox jobs when disabled;
- absence of external telemetry exporters when disabled;
- binding of profile and environment properties;
- use of Boot-managed Jackson configuration by Meta channels.

Workflow assertions will verify both side-effect branches:

```text
handoff enabled  + handoff requested -> handoff_ticket + HandoffRequested
handoff disabled + handoff requested -> conversation turn + OutboundDeliveryRequested
```

No database migration is required.

## Migration and delivery

The implementation is split into focused changes:

1. typed properties and profile/environment contract;
2. deterministic AI composition and unavailable-provider fallback;
3. conversation handoff policy and workflow enforcement;
4. Meta, outbox, ingestion, and observability configuration cleanup;
5. diagnostics and unit/slice coverage;
6. example environment updates and operational documentation.

Existing environment variable names are preserved. The default behavior remains
human handoff enabled, so deployments must explicitly set
`FORD_HUMAN_HANDOFF_ENABLED=false` for bot-only testing.
