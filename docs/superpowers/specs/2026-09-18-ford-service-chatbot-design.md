# ford-service Chatbot Design

| Field | Value |
|---|---|
| Date | 2026-09-18 |
| Status | Design approved in conversation; written-spec review pending |
| Service | `ford-service` |
| Initial market | Ford Uruapan, Mexico |
| Locale/currency | Spanish / MXN |
| Scope | MVP design only; no implementation in this document |

## 1. Purpose

`ford-service` will provide a Spanish-language automotive assistant across
WhatsApp, Facebook Messenger, and Instagram Direct. It will answer questions
about vehicle specifications, colors, prices, inventory, offers, promotions,
and published financing information. It may calculate non-binding estimates,
collect low-sensitivity lead data for optional prequalification, assign a
sales-priority score, and hand likely leads to a human operator.

The score is for sales follow-up only. It must never approve, deny, or decide
credit eligibility.

## 2. Approved architecture

`ford-service` is a standalone Spring Boot modular monolith. It reuses the
architectural lessons from `emme-service` and `emme-nails` without importing
their project-specific modules or infrastructure.

```text
WhatsApp / Messenger / Instagram
        -> channel adapters
        -> normalized message + idempotency
        -> conversation state
        -> intent/routing policy
        -> Spring AI ChatClient + read-only tools
        -> deterministic catalog/finance result
        -> lead capture and score
        -> persisted handoff + operator inbox APIs
        -> channel reply / outbox delivery
```

Initial runtime choices:

- Spring MVC, not WebFlux.
- Java 25 virtual threads for blocking I/O.
- Bounded model/provider admission; virtual threads do not bypass provider or
  database limits.
- Spring AI for model calls, structured extraction, tools, advisors, RAG, and
  provider portability.
- No LangGraph4j dependency in MVP.
- Define a small internal workflow port so LangGraph4j can be added later if
  durable graph behavior becomes necessary.

Spring AI's `ChatClient` and `ToolCallingAdvisor` provide the MVP tool loop.
LangGraph4j is deferred because the first workflow is a small, explicit state
machine rather than a complex, long-running graph.

## 3. Modules

```text
ford-service/
  channels/          Meta webhook validation, normalization, replies
  conversations/     Participants, messages, consent, state
  catalog/            Vehicles, variants, colors, prices, inventory
  offers/             Promotions and validity periods
  finance/            Published products, fees, requirements, estimates
  leads/              Optional profile, score, reasons, outcomes
  handoff/            Operator tickets, assignment, takeover, audit
  ingestion/          CSV/admin, Ford API, authorized scraping, sync runs
  knowledge/          Documents, chunks, embeddings, retrieval
  ai/                 ChatClient, advisors, tools, prompts, model policy
  outbox/              Reliable notifications and future CRM events
  audit/observability Traces, metrics, redaction, AI usage
```

The service exposes operator inbox APIs only. A frontend is outside this MVP.
CRM integration is a future adapter consuming lead/handoff events.

## 4. Channel design

Each provider implements a shared port:

```java
interface ChannelAdapter {
    InboundMessage parseInbound(RawWebhook payload);
    void validateWebhook(WebhookRequest request);
    void sendReply(OutboundMessage message);
}
```

Adapters:

- `WhatsAppChannelAdapter`
- `MessengerChannelAdapter`
- `InstagramChannelAdapter`

Adapters own provider-specific payloads, signature verification, message IDs,
reply capabilities, and delivery retries. They do not own business rules,
vehicle data, financing, scoring, or handoff decisions.

Normalized inbound data:

```text
tenantId
channel
providerMessageId
conversationParticipantId
text
attachments
receivedAt
rawPayloadReference
```

Tenant resolution comes from a registered channel binding, never from user
text or an LLM output. Each channel is independently feature-flagged and
contract-tested even though all three are in the MVP boundary.

## 5. Conversation and handoff state

```text
BOT_ACTIVE -> LEAD_CAPTURE -> HUMAN_PENDING -> HUMAN_ACTIVE -> CLOSED
     ^             |                |              |
     +-------------+----------------+--------------+
                 explicit resume policy
```

State behavior:

| State | Behavior |
|---|---|
| `BOT_ACTIVE` | Answer using approved tools and policies |
| `LEAD_CAPTURE` | Ask only required low-sensitivity questions |
| `HUMAN_PENDING` | Confirm follow-up and notify an operator |
| `HUMAN_ACTIVE` | Stop automatic AI replies |
| `CLOSED` | Archive; reopen only by explicit policy |

A handoff ticket is created when the customer requests a person, the lead is
hot, prequalification is requested, or the system cannot safely answer. The
operator can view evidence, claim the ticket, reply through the original
channel, pause/resume the bot, add notes, and record an outcome.

Delivery behavior:

- duplicate webhook: acknowledge safely without duplicate processing;
- invalid webhook: reject and audit;
- model timeout: deterministic fallback or handoff;
- stale source data: disclose freshness and offer human follow-up;
- outbound failure: retry through the outbox without losing the message.

## 6. Data design

PostgreSQL is the sole primary database for MVP. MongoDB is not needed:
transactions, audit, consent, inventory, offers, finance estimates, leads,
and handoff state are relational. Raw provider documents use PostgreSQL JSONB.

Use `JdbcClient`/SQL for explicit, high-control flows:

- inventory and offer search;
- idempotency and webhook receipts;
- conversation event writes;
- sync upserts;
- outbox claims;
- scoring queries and reporting.

Use Spring Data repositories only when an aggregate becomes complex enough to
benefit from repository mapping. Do not add JPA by default.

Use pgvector for financing documents, vehicle brochures, offer terms, and
policy documents. Use Redis for locks, rate limits, short-lived cache, and
coordination only; Redis is never authoritative.

Initial durable entities:

```text
vehicle_model
vehicle_variant
vehicle_unit
vehicle_color
vehicle_price
offer
finance_product
finance_term
knowledge_document
conversation
conversation_message
lead
lead_score
handoff_ticket
sync_run
outbox_event
audit_event
```

Every imported record includes:

```text
source_system
source_record_id
observed_at
effective_from
effective_to
freshness_status
raw_payload
sync_run_id
```

## 7. Data ingestion

Supported sources:

1. manual/admin or CSV data;
2. official Ford APIs/feeds;
3. authorized Ford scraping;
4. seeded/demo data.

Official API/feed data is primary. Authorized scraping fills fields the feed
does not provide. An approved manual correction may supersede a specific field
only when marked as an explicit override; it requires an expiry or review date.
Seed data is the final demo fallback.

```text
scheduled sync
 -> fetch
 -> validate and normalize
 -> quarantine invalid records
 -> idempotent canonical upsert
 -> update freshness
 -> publish change event
 -> refresh affected embeddings
```

The chatbot never scrapes or queries external Ford systems synchronously. If
source data is unavailable or stale, the response must say so.

## 8. AI design

Use a deterministic-first flow:

```text
rules/classifier
 -> direct deterministic tool when obvious
 -> ChatClient only when language understanding/generation is needed
 -> typed result
 -> domain validation
 -> deterministic application action
```

Typed AI results include `Intent`, `VehicleQuery`, `FinanceQuestion`,
`LeadSignal`, `LeadCaptureRequest`, and `AssistantResponse`.

Read-only model tools:

- `searchVehicles`;
- `getVehicleDetails`;
- `getInventory`;
- `listActiveOffers`;
- `calculateFinanceEstimate`;
- `getFinanceRequirements`;
- `searchKnowledge`.

The model cannot change inventory, create a credit decision, assign the lead
score, send arbitrary messages, choose a tenant/source, or execute SQL.

The LLM is used for interpretation and wording. Catalog facts, finance math,
freshness, consent, scoring, authorization, and state transitions remain
application-owned.

## 9. Lead capture and scoring

Allowed MVP data:

- name and contact;
- preferred vehicle;
- budget and down-payment ranges;
- income/employment ranges;
- trade-in interest;
- consent to contact and optional lender forwarding.

The MVP does not collect exact income, government IDs, bank data, or documents.

Scoring is deterministic and explainable:

```text
signals -> weighted rules -> score band -> reasons -> handoff policy
```

Output bands:

- `HOT`: immediate human follow-up;
- `WARM`: prioritized follow-up;
- `COLD`: nurture or normal queue.

The score only ranks sales follow-up. It never approves, denies, or determines
credit eligibility. Lenders make financing decisions.

## 10. AI/runtime boundary

Use hybrid OOP and functional style:

- OOP for ports, adapters, application services, policies, and integrations;
- pure functions for normalization, scoring, freshness, validation, and
  finance calculations;
- immutable Java records for commands and results.

Patterns selected for concrete problems:

- Adapter: Meta, Ford, lender, CRM;
- Strategy: channel, model, source precedence, scoring;
- State: conversation/handoff lifecycle;
- Command: inbound messages, sync jobs, operator replies;
- Decorator/Chain: auth, tenant scope, redaction, audit, tracing;
- Observer/domain events: outbox and future CRM integration;
- small Factory Method: configured adapter/provider selection.

Avoid Abstract Factory, Service Locator, giant registries, and generic AI
wrappers. Do not use Prolog for the MVP.

## 11. Security and privacy

- verify Meta webhook signatures;
- store credentials in a secret manager or environment configuration;
- resolve tenant from trusted channel binding;
- protect operator APIs with authentication and roles;
- record consent before prequalification forwarding;
- redact PII from logs, traces, prompts, and evaluation data;
- enforce tool allow-lists in application code;
- restrict outbound providers and URLs;
- rate-limit and audit authorized scraping;
- never let the model select tenant, credentials, SQL, or permissions.

## 12. Observability

Every request/workflow carries:

```text
traceId
tenantId
conversationId
messageId
leadId
handoffId
syncRunId
```

Use Micrometer and OpenTelemetry. Do not export full prompts or customer
messages by default.

Required signals:

- webhook success, failure, and duplicate rate;
- channel delivery latency and failures;
- model latency, tokens, cost, and provider health;
- tool success/failure and abstention;
- stale inventory responses;
- sync freshness and quarantine counts;
- lead-score distribution;
- handoff rate and operator response time;
- outbox/queue lag;
- provider rate limits and retries.

Use bounded metric labels. Put high-cardinality IDs in traces or durable
records instead.

## 13. Testing and verification

- Unit tests: scoring, finance calculations, freshness, redaction, and state
  transitions.
- Contract tests: WhatsApp, Messenger, and Instagram payloads/replies.
- Integration tests: PostgreSQL, pgvector, Redis, outbox, and migrations.
- External stubs: WireMock for Ford, lender, and Meta APIs.
- E2E tests: one complete Spanish conversation fixture per Meta channel.
- AI evaluation: fixed vehicle/finance question set for grounding, abstention,
  extraction, and handoff behavior.
- Load tests: MVC + virtual threads against defined SLOs before any WebFlux
  decision.

## 14. Explicit non-goals for MVP

- no credit approval or denial;
- no full financing application;
- no exact financial identity/document collection;
- no synchronous scraping during chat;
- no CRM write integration;
- no frontend operator inbox;
- no MongoDB;
- no WebFlux runtime;
- no LangGraph4j dependency;
- no Prolog rules engine;
- no autonomous mutation tools;
- no multi-country/multi-currency behavior.

## 15. Deferred evolution

Add LangGraph4j only if the workflow grows to require long-running pause/resume
graphs, multiple approval branches, complex cycles, graph replay, or
multi-agent orchestration. If added, start with LangGraph4j core behind the
workflow port; add its Spring AI bridge only for a demonstrated capability gap.

Consider WebFlux only if a fully non-blocking streaming/inbox edge is needed
and load tests show MVC plus virtual threads misses the agreed SLOs.

Consider MongoDB only if real source payloads or product requirements exceed
PostgreSQL JSONB and relational constraints.

## 16. References

- Spring AI tool calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI structured output: <https://docs.spring.io/spring-ai/reference/api/structured-output.html>
- Spring AI effective agent workflows: <https://docs.spring.io/spring-ai/reference/api/effective-agents.html>
- Spring AI chat memory: <https://docs.spring.io/spring-ai/reference/api/chat-memory.html>
- LangGraph4j Spring AI integration: <https://github.com/langgraph4j/langgraph4j/tree/main/spring-ai>
- Spring Boot virtual threads: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>
- Spring WebFlux: <https://docs.spring.io/spring/reference/web/webflux.html>
- OpenJDK virtual threads: <https://openjdk.org/jeps/444>
- Refactoring.Guru Adapter: <https://refactoring.guru/design-patterns/adapter>
- Refactoring.Guru Strategy: <https://refactoring.guru/design-patterns/strategy>
- Refactoring.Guru State: <https://refactoring.guru/design-patterns/state>

## 17. Local references inspected

- `Development/emme-service`: Spring Boot 4.1/Java 25 Modulith, AI contracts,
  Spring AI adapters, LangGraph4j experiments, PostgreSQL/pgvector, Redis,
  persisted HITL, OTel/Micrometer, and assistant conversation boundaries.
- `Development/emme-nails`: tenant-aware service boundaries, webhook
  normalization, provider binding, deduplication, RabbitMQ, Redis, Liquibase,
  PostgreSQL, and OpenTelemetry configuration.
- `Development/Ford-uruapan/ford-ai-service`: empty initial workspace; no
  existing implementation or local repository conventions.
