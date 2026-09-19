# ford-service Phase A: Spring Modulith Workflow Design

| Field | Value |
|---|---|
| Date | 2026-09-18 |
| Status | Draft — awaiting user review |
| Parent design | [ford-service chatbot design](2026-09-18-ford-service-chatbot-design.md) |
| Scope | Secure Meta ingress through durable assistant/handoff delivery |
| Runtime | Spring Boot 4.1.0, Java 25, Spring MVC, virtual threads |
| Persistence | PostgreSQL, JDBC/`JdbcClient`, Liquibase |

## 1. Decision summary

Spring Modulith becomes the structural foundation for `ford-service`. We will
use its supported application-module model, structural verification,
transactional event publication registry, module-sliced tests, scenario tests,
module documentation, runtime verification, actuator module topology, and
module interaction observations.

We will not use Spring Modulith as a replacement for every business table or
for provider delivery. Its event publication registry makes reliable
cross-module event handling possible inside the modular monolith. The existing
business outbox remains the provider-facing delivery boundary because sending
a Meta reply is an external side effect that needs channel-specific idempotency,
provider response IDs, retry backoff, and dead-letter handling.

This gives the following ownership split:

```text
PostgreSQL transaction
  ├─ inbound receipt + conversation/message state
  ├─ Spring Modulith event publication registry
  └─ domain state changes

Spring Modulith listener
  └─ AI/tool/handoff decision outside the ingress transaction

Business outbox
  └─ Meta/WhatsApp/Messenger/Instagram provider delivery
```

The MVP continues to use Spring AI only. LangGraph4j remains deferred until a
durable, long-running, branching workflow proves that the explicit application
workflow is insufficient.

## 2. Why Spring Modulith here

The current code already has domain-shaped packages, but it does not yet enforce
their boundaries, publish durable internal events, or test asynchronous module
interactions. Spring Modulith addresses those gaps with framework capabilities
instead of custom architecture code:

| Problem | Spring Modulith capability | Phase A use |
|---|---|---|
| Package dependencies drift | `ApplicationModules.verify()` | Required build test; no cycles and no access to module internals |
| Cross-module coupling | Application events and `@ApplicationModuleListener` | Inbound acceptance, conversation processing, reply/handoff reactions |
| Crash between commit and listener | JDBC event publication registry | Required; persisted in the same transaction as the source change |
| Retry and recovery of internal listeners | publication lifecycle, staleness, resubmission APIs | Required operational path and tests |
| Full-context tests are slow and coupled | `@ApplicationModuleTest`, `Scenario` | Required module and event-flow tests |
| Architecture is hard to explain | `Documenter` | Generate module diagrams/canvases in CI or a documentation task |
| Runtime topology is hidden | Modulith actuator endpoint | Expose only through the protected management surface |
| Module interactions lack traces | Modulith observability | Feed the existing OpenTelemetry/Langfuse operational setup |
| Migration ownership is unclear | Modulith module-aware Flyway | Not enabled: this project already uses Liquibase; do not run two migration systems |
| Time-based domain events | Moments/TimeMachine | Not enabled for Phase A; no current product rule requires it |
| External broker integration | Modulith event externalization | Not enabled in Phase A; future CRM/broker integration can adopt it |

Spring Modulith’s documented model treats module APIs and published events as
provided interfaces, while internal implementation types stay hidden. Its
verification checks for cycles, API-only access, and explicitly allowed
dependencies. See the [fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
and [verification](https://docs.spring.io/spring-modulith/reference/verification.html)
documentation.

## 3. Version and dependency decision

Use the Spring Modulith BOM at `2.1.1`, aligned with the project’s Spring Boot
4.1.x line. The dependency task must resolve and compile against the exact
project version before implementation continues. If the project’s selected Boot
patch requires a different compatible Modulith patch, the version catalog is
the single place to adjust it; no individual Modulith artifacts may carry
independent versions.

Planned Gradle dependencies:

```text
implementation(platform(spring-modulith-bom))
implementation(spring-modulith-starter-jdbc)
runtimeOnly(spring-modulith-starter-insight)
testImplementation(spring-modulith-starter-test)
```

The JDBC starter is intentional: this service uses JDBC and PostgreSQL as its
primary store. It provides the core module support plus the JDBC event
publication registry and Jackson event serialization. The insight starter
provides Modulith actuator and module interaction observations on top of the
existing Actuator/OpenTelemetry setup. The test starter provides module slicing,
`PublishedEvents`, and `Scenario` support. These artifact contents are listed in
the [official Modulith appendix](https://docs.spring.io/spring-modulith/reference/appendix.html).

The official stable release documentation currently identifies 2.1.1 as the
latest stable line; Boot compatibility is still verified by Gradle as part of
the first implementation task. Do not use a milestone or snapshot in the MVP.

## 4. Module arrangement

The existing direct subpackages become Modulith application modules. Technical
configuration remains outside the business module graph where possible.

```text
shared
  ↑
catalog   offers   finance   knowledge   leads
     \       |       |          /          /
                  ai
                   ↑
              conversations ← channels
                   ↓
                handoff
                   ↓
                 outbox

ingestion → catalog, offers, finance, knowledge
observability is a shared technical module consumed by application modules
config is excluded from business-module verification
```

The exact edge list must be made acyclic before enabling strict verification.
Cross-module references use only public API packages or named interfaces. SQL
repositories and provider adapters remain internal to their owning module.

Required module rules:

- `channels` owns Meta verification, binding resolution, payload normalization,
  receipt idempotency, and inbound acceptance.
- `conversations` owns conversation identity, state transitions, turn
  processing, and the decision to persist an assistant reply or request a
  handoff.
- `ai` owns Spring AI composition, provider selection, prompt/advisor policy,
  and read-only tool exposure. It does not own lead scoring or state changes.
- `catalog`, `offers`, `finance`, and `knowledge` expose query APIs and domain
  events; their persistence adapters are internal.
- `handoff` owns operator tickets and human takeover state.
- `outbox` owns reliable external notification delivery and provider delivery
  adapters needed by the delivery port. It does not reach into channel module
  internals.
- `ingestion` owns source synchronization and publishes canonical-data change
  events; it never runs during a customer request.
- `shared` contains immutable contracts and cross-cutting primitives only; it
  must not become a service locator or a dumping ground.

Package-private implementation types and `package-info.java`/`@ApplicationModule`
metadata will express the API boundary. `ApplicationModules.of(FordServiceApplication.class).verify()`
will be a mandatory test and runtime verification will be enabled for deployed
profiles.

## 5. Event model and transaction boundaries

### 5.1 Inbound acceptance

The public endpoint changes to an opaque binding route:

```text
POST /webhooks/meta/{channel}/{webhookKey}
```

The request does not accept a tenant header. The binding registry resolves the
tenant, provider account ID, secret reference, and enabled channel from the
opaque key. The adapter verifies the signature against the binding credential
before parsing the payload and validates the provider account identifier.

The `channels` application service runs one short transaction:

1. Resolve and authenticate the binding.
2. Deduplicate by binding/provider message identity.
3. Create or find the conversation identity.
4. Persist the normalized inbound message.
5. Publish `InboundMessageAccepted`.
6. Commit the receipt and the Modulith event publication entry together.

The endpoint returns `202 Accepted` after commit. Duplicate deliveries return a
safe acknowledgment and do not publish a second processing event.

### 5.2 Conversation processing

`conversations` consumes `InboundMessageAccepted` using
`@ApplicationModuleListener`. The listener is asynchronous on the configured
virtual-thread executor and starts its own transaction only for short state
reads/writes. The model call, retrieval, and deterministic tools execute
outside any database transaction.

The listener then performs a second short transaction with a version/CAS guard:

- if the conversation is still `BOT_ACTIVE` or `LEAD_CAPTURE`, persist the
  assistant message and an outbox delivery event;
- if lead policy requires a person, transition to `HUMAN_PENDING`, persist a
  handoff ticket, and publish an operator notification event;
- if an operator claimed the conversation during model execution, discard the
  stale assistant result and record the reason;
- if the model fails, persist a deterministic safe fallback or handoff outcome.

The AI call must never hold an open JDBC transaction. This preserves the
virtual-thread choice while preventing connection starvation from model latency.

### 5.3 Internal event reliability

The Spring Modulith JDBC event publication registry records transactional
listeners with the source transaction. Failed or stale publications remain
recoverable through the registry lifecycle. Configure non-zero staleness
thresholds, expose failure metrics, and provide an operator/admin resubmission
path using the official `FailedEventPublications`/`IncompleteEventPublications`
APIs.

The registry is for reliable internal listener execution. It is not the Meta
delivery idempotency key and not a substitute for the business `outbox_event`
record.

Relevant properties are explicitly documented in configuration:

```yaml
spring:
  modulith:
    runtime:
      verification-enabled: true
    events:
      completion-mode: update
      externalization:
        enabled: false
      staleness:
        check-interval: 1m
        processing: 10m
        published: 10m
        resubmitted: 10m
      republish-outstanding-events-on-restart: false
```

The exact property names must be validated against the resolved Modulith
version during implementation. Multi-instance deployments must use explicit
claim/lease behavior and operator-controlled resubmission; automatic
republish-on-restart is not enabled by default.

## 6. AI and handoff boundaries

Spring AI remains the model abstraction. The `ai` module exposes a narrow
application port to `conversations`. Its Spring AI tools are read-only:

- vehicle snapshot/details;
- current inventory and colors;
- active offers;
- deterministic finance estimate;
- approved-document retrieval for published requirements/fees.

Tenant, locale, source filters, effective dates, and freshness are injected by
the application context. They are not model-controlled arguments. Finance
results are non-binding and never a credit decision.

The model does not create a handoff, change conversation state, send a message,
assign a lead score, mutate inventory, or access SQL. `conversations` applies
the deterministic lead score and handoff policy after the model result.

Spring Modulith events used by this flow are immutable records with stable IDs:

```text
InboundMessageAccepted
ConversationTurnReady
AssistantReplyPrepared
HumanHandoffRequested
OutboundDeliveryRequested
```

Only events crossing a module boundary are published. Private implementation
events stay private. The outbox event carries a stable business idempotency key
derived from tenant, conversation, and logical message ID.

## 7. Observability

Every inbound receipt, internal event publication/listener, model invocation,
tool call, retrieval, handoff transition, and provider delivery gets:

- trace/span correlation;
- tenant-safe conversation correlation;
- module and event type;
- provider/model/tool name where applicable;
- duration, outcome, retry count, and error category;
- redacted prompts/completions and no credentials or raw sensitive payloads.

Use Modulith’s module interaction observations for module-level traces and
metrics, existing Spring/OpenTelemetry instrumentation for HTTP/JDBC, existing
AI observation hooks for provider calls, and Langfuse for AI-specific traces
and evaluations. No extra custom event-log subsystem is needed.

The Modulith actuator topology endpoint is management-plane data. It is not
publicly exposed through the customer API and remains protected by the existing
management security policy.

## 8. Testing strategy

Required tests before Phase A is complete:

| Test | Mechanism | Proof |
|---|---|---|
| Module graph | `ApplicationModules.verify()` | No cycles; no internal-package access |
| Module bootstrap | `@ApplicationModuleTest` | Channels, conversations, AI, handoff, and outbox slice independently |
| Internal event flow | `Scenario` | Accepted inbound event reaches processing and expected outcome |
| Event persistence | PostgreSQL/Testcontainers | Event publication row is committed with source state |
| Listener failure | registry API + test listener | Failure remains retryable and resubmission is observable |
| Human race | integration test | stale model result cannot send after human claim |
| Provider delivery | outbox integration test | lease/retry/idempotency/provider ID behavior remains durable |
| Webhook security | MockMvc + real binding repository | no caller-controlled tenant; signature/account binding enforced |
| Architecture docs | `Documenter` test/task | module diagrams and canvases are generated |
| Runtime topology | actuator contract test | topology endpoint is available only on management access |

`Scenario` is preferred for asynchronous transactional event tests because it
provides transaction-aware stimuli and bounded waiting. Database integration
tests use Testcontainers PostgreSQL for the event registry, Liquibase schema,
CAS behavior, and outbox. Unit tests use injected fakes for model providers,
retrieval, delivery, and clock.

## 9. Explicit non-goals

- No LangGraph4j in this phase.
- No MongoDB or JPA introduction.
- No public CRM integration yet.
- No automatic credit approval, rejection, or lender submission.
- No synchronous scraping during chat requests.
- No custom replacement for Modulith’s module verification, event registry,
  module test support, actuator topology, or module observations.
- No use of Modulith Moments or module-aware Flyway until a real requirement
  justifies them.

## 10. Implementation gates

Implementation may begin only after this design is reviewed. The first coding
task is dependency resolution plus a failing module-verification test. If the
existing package graph cannot pass verification without a broad redesign, stop
and update this design before changing production behavior.

### Official references

- [Spring Modulith overview and BOM](https://docs.spring.io/spring-modulith/reference/)
- [Application modules and fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- [Module verification](https://docs.spring.io/spring-modulith/reference/verification.html)
- [Application events and publication registry](https://docs.spring.io/spring-modulith/reference/events.html)
- [Module integration tests and Scenario](https://docs.spring.io/spring-modulith/reference/testing.html)
- [Module documentation generation](https://docs.spring.io/spring-modulith/reference/documentation.html)
- [Runtime and module-aware infrastructure](https://docs.spring.io/spring-modulith/reference/runtime.html)
- [Actuator and module observability](https://docs.spring.io/spring-modulith/reference/production-ready.html)
- [Artifact and compatibility appendix](https://docs.spring.io/spring-modulith/reference/appendix.html)
