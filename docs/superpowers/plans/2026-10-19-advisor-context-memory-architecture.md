# Advisor Context and Customer Memory Implementation Plan

> **For agentic workers:** Implement inline, task by task, using test-first checkpoints. Do not spawn subagents. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Preserve separate Meta conversation threads while sharing explicit, fresh shopping facts across a verified same-tenant customer identity and preventing repeated replies.

**Architecture:** Keep the current conversation key and transcript authoritative. Add a keyed-fingerprint customer identity and structured fact profile across provider accounts; assemble that profile with bounded thread memory and the existing lead journey into provider-neutral context. Enforce a repeat-only quality guard independently of the optional broader response-verification flag.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, LangGraph4j, PostgreSQL/Liquibase, pgvector only where the existing memory implementation already uses it, JUnit 5, AssertJ, Gradle.

## Global Constraints

- Keep tenant, channel, Meta provider account, and participant in every conversation, transcript, cache, and retrieval scope.
- Keep conversation transcripts separate when Meta `phone_number_id` changes; do not merge conversation IDs.
- Share only explicit shopping facts: vehicle model/trim, stated budget, purchase timeframe, and exact declined preferences/offers.
- Active shared facts expire 90 days after the latest explicit confirmation/update; declined suppression has no automatic expiry and ends only on explicit reopen or forget.
- Never infer or store customer memory from assistant output; fact source must be an explicit customer message and source reference.
- Do not store credit identifiers, account numbers, CURP/RFC, credit score, income, provider credentials, prompts, or raw message text in the shared profile.
- An explicit thread restart preserves the transcript and shared customer facts; forgetting customer facts and transcript deletion are distinct operations.
- This plan does not implement transcript deletion; any transcript-erasure workflow needs its own approved retention/privacy policy and plan.
- Keep a minimum exact-repeat guard active in production even when `FORD_AI_RESPONSE_VERIFICATION_ENABLED=false`.
- Keep DeepSeek as the baseline; pass one provider-neutral context to DeepSeek and all eligible fallbacks.
- No production fact writes, feature enablement, transcript deletion, or memory reset until the protected rollout gates pass.
- Production/customer replay sends no messages and writes no transcript or customer profile data.
- Run unit tests first; run integration tests only against a dedicated `_test` database. Never fall back to `DATABASE_URL` or production credentials.

**Approved design:** `docs/superpowers/specs/2026-10-19-advisor-context-memory-architecture-design.md`

---

## File Map

### Customer identity and profile storage

- Create `src/main/java/com/ford/service/conversations/domain/CustomerMemoryIdentity.java` — internal tenant-scoped stable customer-memory identity.
- Create `src/main/java/com/ford/service/conversations/domain/CustomerShoppingFact.java` and `CustomerShoppingFactState.java` — current typed fact, expiry, provenance, version, and `ACTIVE`/`DECLINED` state.
- Create `src/main/java/com/ford/service/conversations/domain/CustomerShoppingFactType.java` — allowlisted fact types.
- Create `src/main/java/com/ford/service/conversations/domain/CustomerShoppingFactChange.java` — content-minimal state-change audit record.
- Create `src/main/java/com/ford/service/conversations/api/CustomerIdentityFingerprint.java` — public, provider-independent WhatsApp normalization and versioned HMAC-SHA-256 fingerprint contract.
- Create `src/main/java/com/ford/service/conversations/application/CustomerIdentityBackfill.java` — link existing conversation rows without outputting raw participant identifiers.
- Create `src/main/java/com/ford/service/conversations/application/port/out/CustomerMemoryIdentityRepository.java` and `src/main/java/com/ford/service/conversations/adapter/out/JdbcCustomerMemoryIdentityRepository.java` — tenant/channel-scoped identity resolution, provider-account-independent conversation links, safe cursor paging.
- Create `src/test/java/com/ford/service/conversations/CustomerIdentityBackfillTest.java`, `src/integrationTest/java/com/ford/service/conversations/CustomerMemoryIdentityIntegrationTest.java`, and `CustomerMemoryChangelogIntegrationTest.java` — prove safe rotation, linking, migration privacy, and backfill behavior.
- Create `src/main/java/com/ford/service/conversations/application/CustomerMemoryProperties.java` — feature flag, active/previous HMAC key versions, active-fact TTL, and context count/size bounds.
- Create `src/main/java/com/ford/service/conversations/application/CustomerMemoryService.java` — link conversation to identity, capture explicit facts, load relevant facts, reopen/forget facts.
- Create `src/main/java/com/ford/service/conversations/application/CustomerMemoryFactExtractor.java` — deterministic extraction of explicit model/trim, budget, timeframe, and scoped decline from the current customer message only.
- Create `src/main/java/com/ford/service/conversations/application/CustomerMemoryUpdatePolicy.java` — validate deterministic extracts and apply explicit updates/declines only.
- Create `src/main/java/com/ford/service/conversations/application/port/out/CustomerMemoryRepository.java` — persistence contract for identity links, current facts, and audit events.
- Create `src/main/java/com/ford/service/conversations/adapter/out/JdbcCustomerMemoryRepository.java` — parameterized PostgreSQL adapter.
- Create `src/main/resources/db/changelog/changes/028-customer-memory-profile.yaml` and include it in `db/changelog/db.changelog-master.yaml` — identity, versioned fingerprint, link, current fact, and audit tables/indexes.

### Context and reply continuity

- Create `src/main/java/com/ford/service/conversations/api/AdvisorContext.java` — immutable provider-neutral aggregate of current message, thread memory, shared customer facts, and lead journey.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java` — keep thread-memory reads unchanged and capture explicit inbound facts idempotently without changing conversation identity.
- Modify `src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java` — load relevant shared facts and build/render one `AdvisorContext` for both graph and legacy workflow paths.
- Modify `src/main/java/com/ford/service/conversations/application/LeadJourneyContextFormatter.java` only if necessary — retain journey state as a separate prompt section; do not duplicate shared facts there.
- Create `src/main/java/com/ford/service/shared/AdvisorResponseRepeatPolicy.java` — dependency-free exact normalization and conservative semantic-overlap decision shared by AI and conversation modules.
- Modify `src/main/java/com/ford/service/ai/application/AiExecutionContext.java`, `src/main/java/com/ford/service/ai/api/AssistantRequest.java`, `src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`, `src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`, and `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java` — carry prior assistant replies as structured context, run the repeat-only check after provider routing, permit one provider-neutral repair call with the same context, and keep the existing broad verification flag scoped to other checks.
- Modify `src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java` and `ConversationWorkflow.java` — final pre-commit check after response planning so deterministic appended copy cannot bypass the guard; repeated final output remains unsent and unresolved.

### Explicit restart, forget, and old Meta thread behavior

- Create `src/main/java/com/ford/service/conversations/api/ConversationMemoryRestartRequest.java` and `CustomerMemoryForgetReceipt.java` — reason code and safe receipt contracts.
- Create `src/main/java/com/ford/service/conversations/application/CustomerMemoryForgetService.java` — authorized, idempotent forget operation resolved from a conversation ID.
- Create `src/main/java/com/ford/service/conversations/adapter/in/web/CustomerMemoryController.java` — tenant-scoped operator API for forgetting shared facts.
- Modify `docs/openapi/frontend-api.yaml` and `src/test/java/com/ford/service/config/OpenApiContractTest.java` — document restart reason and forget-facts endpoint contract.
- Modify `src/main/java/com/ford/service/conversations/adapter/in/web/ConversationController.java` and `src/main/java/com/ford/service/conversations/application/ConversationMemoryRestartService.java` — require and persist a restart reason without changing the thread transcript or shared facts.
- Modify `src/main/java/com/ford/service/conversations/adapter/out/JdbcOperatorConversationCommandRepository.java` and `src/main/java/com/ford/service/conversations/application/DefaultConversationCommandService.java` — reject outbound operator sends to conversations whose Meta channel binding is disabled.
- Modify `src/main/java/com/ford/service/conversations/api/ConversationView.java` and `src/main/java/com/ford/service/conversations/adapter/out/JdbcOperatorConversationRepository.java` only as needed to expose a `readOnly` indicator for disabled-binding historical threads.
- Create `src/test/java/com/ford/service/conversations/CustomerMemoryControllerTest.java` and `JdbcOperatorConversationRepositoryTest.java` — test authorized forget and the read-only presentation contract.
- Add Liquibase audit columns/table to `028-customer-memory-profile.yaml` for forget events and restart reason records; do not store message text in audit rows.

### Verification and private replay

- Create `scripts/ai/advisor-memory-shadow-replay.mjs` and `scripts/ai/advisor-memory-shadow-replay.test.mjs` — private production-snapshot replay and synthetic-only runner tests.
- Create `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerMemoryPropertiesTest.java`, `CustomerMemoryUpdatePolicyTest.java`, `CustomerIdentityFingerprintTest.java`, and `ford-ai-service/src/test/java/com/ford/service/shared/AdvisorResponseRepeatPolicyTest.java` — unit coverage for configuration, explicit fact rules, and duplicate handling.
- Create PostgreSQL integration tests under `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/` for identity linking, fact version/expiry/state, restart isolation, and disabled Meta binding behavior.

## Task 1: Add Tenant-Scoped Identity and Versioned HMAC Lookup

**Consumes:** Existing `ConversationMemoryKey` and conversation rows, which retain tenant/channel/provider-account/participant identity.

**Produces:** A stable internal `CustomerMemoryIdentity` that links multiple provider-account-specific conversations without merging transcripts.

**Status:** Complete. Implementation is limited to durable identity/link primitives; profile writes remain disabled by default. See per-step actions and verification below.

**Interfaces:**
- `CustomerIdentityFingerprint.fingerprint(UUID tenantId, Channel channel, String participantId, int keyVersion, byte[] key): String`
- `CustomerMemoryIdentityRepository.VersionedFingerprint(int keyVersion, String fingerprint)` and `CustomerIdentityBackfill.BackfillResult(int scanned, int linked, int skipped)`.
- `CustomerMemoryIdentityRepository.resolveOrCreateIdentity(UUID tenantId, Channel channel, List<VersionedFingerprint> fingerprints): CustomerMemoryIdentity`
- `CustomerMemoryIdentityRepository.linkConversation(UUID tenantId, Channel channel, UUID identityId, UUID conversationId): void`
- `CustomerIdentityBackfill.run(int batchSize): BackfillResult`

- [x] **Step 1: Add failing fingerprint and isolation tests.**

  - **Actions Applied**
    - `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerIdentityFingerprintTest.java` — normalization, tenant/channel scope, key rotation, provider-account independence, malformed IDs, and HMAC contract.
  - **Verification**
    - RED: focused Gradle run initially failed compilation because the new identity classes were absent.

- [x] **Step 2: Run focused tests and confirm the missing implementation fails.**
  - **Actions Applied**
    - Ran focused fingerprint and properties tests before adding production classes.
  - **Verification**
    - Gradle failed at test compilation with the expected missing-symbol errors.

- [x] **Add configuration, backfill, integration, and architecture boundary tests.**
  - **Actions Applied**
    - `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerMemoryPropertiesTest.java` — feature configuration, secure key length/version, key decoding, retention and context bounds.
    - `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerIdentityBackfillTest.java` — current/previous key versions, processed counts, and batch-size guard.
    - `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/CustomerMemoryIdentityIntegrationTest.java` and `CustomerMemoryChangelogIntegrationTest.java` — PostgreSQL link/rotation/tenant tests and migration privacy contract.
    - `ford-ai-service/src/test/java/com/ford/service/architecture/ModuleApiBoundaryTest.java` — public fingerprint API remains type-scoped; implementation packages remain private.
  - **Verification**
    - Focused unit and integration tests passed. Full unit suite: `./gradlew test --rerun-tasks --no-build-cache --max-workers=1` — **871 tests passed**.

`CustomerIdentityFingerprintTest` proves:

```java
assertThat(fingerprint.of(TENANT, Channel.WHATSAPP, "523331733925", keyV1))
        .isEqualTo(fingerprint.of(TENANT, Channel.WHATSAPP, "52 333 173 3925", keyV1));
assertThat(fingerprint.of(TENANT_A, Channel.WHATSAPP, PARTICIPANT, keyV1))
        .isNotEqualTo(fingerprint.of(TENANT_B, Channel.WHATSAPP, PARTICIPANT, keyV1));
assertThat(fingerprint.of(TENANT, Channel.WHATSAPP, PARTICIPANT_A, keyV1))
        .isNotEqualTo(fingerprint.of(TENANT, Channel.WHATSAPP, PARTICIPANT_B, keyV1));
```

Provider-account IDs are not inputs to the customer fingerprint; a different HMAC key produces a different fingerprint. `CustomerMemoryPropertiesTest` checks shared profile is disabled by default, enabled configuration requires a >=32-byte active key, and previous-key configuration requires a version.

- [x] **Step 3: Add the Liquibase identity/link schema and property binding.**

  - **Actions Applied**
    - `ford-ai-service/src/main/resources/db/changelog/changes/028-customer-memory-profile.yaml` and `db.changelog-master.yaml` — tenant/channel-scoped identity, versioned HMAC fingerprints, conversation links, and identity-level forget boundary.
    - `application.yml`, `application-prod.yml`, and `ConversationConfiguration.java` — bind secret-backed settings with `enabled=false` by default.
  - **Verification**
    - Liquibase integration test applied migration 028 and verified no raw participant/phone column exists.

Create `028-customer-memory-profile.yaml` with:

- `customer_memory_identity(id, tenant_id, channel, facts_forget_before, created_at)`.
- `customer_memory_identity_fingerprint(customer_identity_id, tenant_id, channel, key_version, participant_fingerprint, created_at)`; unique `(tenant_id, channel, key_version, participant_fingerprint)` so rotation can temporarily retain multiple key versions per identity.
- `customer_memory_identity_conversation(customer_identity_id, tenant_id, conversation_id, linked_at)`; unique `conversation_id`; FK to `conversation` and identity.
- Index tenant/channel/fingerprint lookup and tenant/identity link lookup.
- No raw WhatsApp number column in the profile identity table.
- Backfill reads only conversations tied to an existing tenant/channel/provider-account binding and skips legacy/unbound rows.

Use these key constraints in Liquibase (include FK details, indexes, and rollback in the changeset):

```sql
create table customer_memory_identity (
    id uuid primary key,
    tenant_id uuid not null,
    channel varchar(30) not null,
    created_at timestamptz not null,
    unique (id, tenant_id, channel)
);
create table customer_memory_identity_fingerprint (
    customer_identity_id uuid not null references customer_memory_identity(id),
    tenant_id uuid not null,
    channel varchar(30) not null,
    key_version integer not null,
    participant_fingerprint char(64) not null,
    created_at timestamptz not null,
    unique (tenant_id, channel, key_version, participant_fingerprint)
);
create table customer_memory_identity_conversation (
    tenant_id uuid not null,
    customer_identity_id uuid not null references customer_memory_identity(id),
    conversation_id uuid primary key references conversation(id),
    linked_at timestamptz not null
);
```

Include migration 028 in `db.changelog-master.yaml`. Add `CustomerMemoryProperties` with shared-profile writes disabled by default; reject enabling it when the current HMAC key is absent or shorter than 32 random bytes. Support a current key/version and optional previous key/version during rotation. Bind `FORD_CUSTOMER_MEMORY_ENABLED`, `FORD_CUSTOMER_MEMORY_HMAC_KEY`, `FORD_CUSTOMER_MEMORY_HMAC_KEY_VERSION`, and optional previous-key settings in `application.yml` and `application-prod.yml` without a default secret.

- [x] **Step 4: Implement identity fingerprint and repository linking.**

  - **Actions Applied**
    - `CustomerIdentityFingerprint.java` in the public API package — WhatsApp digits normalization and tenant-scoped HMAC-SHA-256.
    - `JdbcCustomerMemoryIdentityRepository.java` — safe fingerprint conflict detection, tenant-safe links, stable conversation identity, and provider-account-independent profile identity.
  - **Verification**
    - Focused PostgreSQL integration test links two distinct account-specific conversation IDs to the same customer identity.

Normalize WhatsApp participant identifiers to digits only. Compute HMAC-SHA-256 over the tenant/channel-scoped canonical value using `javax.crypto.Mac`. `resolveOrCreateIdentity(...)` checks current and configured previous fingerprints; a previous-key match adds the current fingerprint to the same identity. `linkConversation(...)` is idempotent by conversation ID. Never log the participant, key, or fingerprint.

The fingerprint service selects the configured key by version and computes HMAC over the canonical input:

```java
var canonical = tenantId + ":" + channel + ":" + digitsOnlyParticipant;
var mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(keyBytesFor(keyVersion), "HmacSHA256"));
var fingerprint = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(UTF_8)));
```

- [x] **Step 5: Add the existing-conversation fingerprint backfill and rotation contract.**

  - **Actions Applied**
    - `CustomerIdentityBackfill.java` — batches WhatsApp conversations only when bound provider-account evidence exists; emits aggregate counts and avoids logging participant, keys, or fingerprints.
    - Backfill computes current and optional previous version fingerprints and preserves conversation IDs.
  - **Verification**
    - Unit coverage validates both key versions and rejects invalid batch sizes.

Backfill existing conversations by paging only `tenant_id`, `channel`, `participant_id`, and `conversation_id` inside the application process; upsert versioned fingerprints and conversation links. Print only processed/linked/skipped counts. Test current+previous key versions resolving to one identity and an idempotent backfill. Keep the previous key configured until every identity has the current version; remove it only after aggregate coverage passes.

- [x] **Step 6: Run focused unit and database integration tests.**

  - **Actions Applied**
    - Ran focused unit, architecture-boundary, Liquibase, and identity PostgreSQL integration tests.
  - **Verification**
    - All focused unit and PostgreSQL integration tests passed; no database URL was used (Testcontainers only).

## Task 2: Store Explicit Shopping Facts with Change, Decline, Expiry, and Forget Semantics

**Consumes:** Tenant-scoped identity and repository from Task 1.

**Status:** Complete for fact capture, persistence, lifecycle, expiry, idempotency, and forget semantics. Inbound capture is wired in Task 3 and remains disabled by default.

**Produces:** Idempotent current facts plus content-minimal audit events; active facts expire after 90 days and explicit `DECLINED` suppression persists until reopen/forget.

**Interfaces:**
- `CustomerShoppingFactType` allowlist: `VEHICLE_MODEL`, `VEHICLE_TRIM`, `SHOPPING_BUDGET`, `PURCHASE_TIMEFRAME`, and `PROMOTION_DECLINE`.
- Nested `CustomerMemoryFactProposal(CustomerShoppingFactType type, String subjectKey, String value, String exactUserEvidence, CustomerShoppingFactState state)` record in `CustomerMemoryFactExtractor`.
- `CustomerMemoryFactExtractor.extract(String customerMessage): List<CustomerMemoryFactExtractor.CustomerMemoryFactProposal>`
- `CustomerMemoryUpdatePolicy.validate(String customerMessage, CustomerMemoryFactProposal proposal): Optional<CustomerShoppingFact>`
- `CustomerMemoryService.captureExplicitFacts(UUID tenantId, UUID conversationId, ConversationMemoryKey key, UUID sourceReceiptId, String customerMessage): void`
- `CustomerMemoryRepository.upsertFact(CustomerShoppingFact fact, long expectedVersion): MemoryUpdateResult`

- [x] **Step 1: Add failing fact-policy tests.**

Cover these cases in `CustomerMemoryUpdatePolicyTest`:

- Exact customer evidence supports model/trim, budget, or purchase-timeframe proposal; missing/non-matching evidence is rejected.
- Assistant-only content never creates or changes profile facts.
- Explicit decline of a named model sets that exact model to `DECLINED` with no automatic expiry.
- Declining one offer suppresses only that offer ID; it does not suppress future vehicle offers.
- A later explicit customer statement reopens/replaces the fact and updates source/time.
- Active fact expires 90 days after its last explicit update; decline does not expire until reopen/forget.
- A delayed older inbound projection cannot overwrite a fact sourced from a newer customer message.
- Reprocessing the same source message ID is a no-op.
- Credit identifiers, account numbers, CURP/RFC, credit score, income, and raw message text are rejected from shared storage.

  - **Actions Applied**
    - `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerMemoryUpdatePolicyTest.java` — exact evidence, sensitive values, model/offer decline, explicit reopen, expiry, forget boundary, and bare model rejection.
    - `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerMemoryServiceTest.java` — receipt-idempotent capture and identity linking.
  - **Verification**
    - Confirmed RED for missing APIs, and later a dedicated RED test for bare model mention.

- [x] **Step 2: Run the fact-policy tests and confirm the expected red state.**

```bash
./gradlew test --tests 'com.ford.service.conversations.CustomerMemoryUpdatePolicyTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: tests fail because the typed fact policy and storage do not exist.

  - **Actions Applied**
    - Ran `CustomerMemoryUpdatePolicyTest` before creating the fact API and extractor.
  - **Verification**
    - Gradle failed at test compilation for the expected missing fact policy/types.

- [x] **Step 3: Add current-fact and change-event tables.**

Extend migration 028 with `customer_memory_fact` keyed by identity, fact type, and normalized subject key. Store bounded value, `ACTIVE`/`DECLINED` state, source conversation, source inbound receipt/message, source-received/stated/updated timestamps, nullable expiry (`NULL` for declined suppression), and optimistic version. Add `customer_memory_fact_event` with fact type, prior/new state, source message reference, timestamp, and source type; do not duplicate message content or fact values in the event table. Add `facts_forget_before` to the customer identity as a non-content boundary that prevents backfills from recreating forgotten facts from older transcript rows. Add indexes for active facts by identity and expiry cleanup.

Use a unique current-fact key `(customer_identity_id, fact_type, subject_key)` and compare-and-set updates by `version`. Accept an update only when its inbound `source_received_at` is later than the current fact's source timestamp; on equal timestamps use the stable source receipt ID as a deterministic tie-break. `ACTIVE` rows set `expires_at = stated_at + interval '90 days'`; `DECLINED` rows set `expires_at = null`. Forgetting deletes all fact values and advances identity-level `facts_forget_before`; older transcript rows cannot recreate facts after that boundary.

  - **Actions Applied**
    - `ford-ai-service/src/main/resources/db/changelog/changes/028-customer-memory-profile.yaml` — fact/current event tables, bounded columns, current key, source idempotency, and expiry index.
  - **Verification**
    - Liquibase Testcontainers migration test applies migration 028 and checks the identity privacy contract.

- [x] **Step 4: Implement explicit-evidence validation and idempotent updates.**

Create `CustomerMemoryFactExtractor` to return facts only when deterministic patterns identify allowlisted values in the current customer message. Support explicit model/trim interest, numeric budget with shopping context, explicit purchase timeframe, explicit model/trim decline, and decline of a specific offer ID. Return no fact for ambiguous or assistant-only text. `CustomerMemoryUpdatePolicy` validates each extracted value/state and rejects source messages at or before `facts_forget_before`; then `CustomerMemoryService.captureExplicitFacts(UUID tenantId, UUID conversationId, ConversationMemoryKey key, UUID sourceReceiptId, Instant sourceOccurredAt, String customerMessage)` writes it idempotently using the inbound receipt/message ID as source.

The extractor returns typed evidence from the current inbound message only:

```java
public record CustomerMemoryFactProposal(
        CustomerShoppingFactType type,
        String subjectKey,
        String value,
        String exactUserEvidence,
        CustomerShoppingFactState state) {}
```

Reject a proposal unless `customerMessage.contains(exactUserEvidence)`, the fact type is allowlisted, its value is bounded, and sensitive patterns are absent. Never use an assistant reply or generated summary as evidence.

  - **Actions Applied**
    - `CustomerMemoryFactExtractor.java`, `CustomerMemoryUpdatePolicy.java`, `CustomerMemoryService.java`, and `CustomerShoppingFact.java` — deterministic bounded proposals, current-message evidence, WhatsApp-only capture, HMAC-linked identity resolution, and safe logging.
    - `CustomerMemoryFactRepository.java` and `JdbcCustomerMemoryFactRepository.java` — serialized updates, stable source ordering, same-receipt replay checks, and content-minimal events.
  - **Verification**
    - Focused fact policy/service tests and architecture test passed. Bare model mention RED then GREEN; implicit mentions are no longer retained.

- [x] **Step 5: Implement expiry, decline, reopen, and forget repository behavior.**

Load only active facts whose `expires_at > now()` plus non-expiring declined suppressions. Expire active facts without deleting the transcript. On forget, delete all shared fact values, advance `facts_forget_before`, and write a content-minimal idempotent audit event; do not retain a per-fact forgotten tombstone or source text. Only explicit customer messages received after the forget boundary may create new shared facts.

  - **Actions Applied**
    - Fact reads omit expired active facts and preserve unexpired declines; explicit reopen removes only the matching declined subject.
    - Forget removes current shared facts, advances the identity boundary monotonically, and writes one content-minimal operator event.
  - **Verification**
    - PostgreSQL integration test proves update ordering, stale-write rejection, decline/reopen, forget deletion, stale replay rejection, and post-forget write acceptance.

- [x] **Step 6: Run unit and PostgreSQL integration tests.**

```bash
./gradlew test --tests 'com.ford.service.conversations.CustomerMemoryUpdatePolicyTest' --tests 'com.ford.service.conversations.CustomerMemoryServiceTest' --rerun-tasks --no-build-cache --max-workers=1
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
./gradlew integrationTest --tests 'com.ford.service.conversations.CustomerMemoryFactIntegrationTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: all fact lifecycle, exact evidence, decline persistence, 90-day active expiry, idempotency, optimistic concurrency, and tenant isolation assertions pass.

  - **Actions Applied**
    - Ran full service unit suite and focused Testcontainers memory integrations.
  - **Verification**
    - `./gradlew test --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL** (882 tests).
    - `./gradlew integrationTest --tests 'com.ford.service.conversations.CustomerMemoryFactIntegrationTest' --tests 'com.ford.service.conversations.CustomerMemoryChangelogIntegrationTest' --tests 'com.ford.service.conversations.CustomerMemoryIdentityIntegrationTest' --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL**.

## Task 3: Link Incoming Threads and Assemble One Bounded Provider-Neutral Advisor Context

**Consumes:** Identity and fact service from Tasks 1–2; existing `ConversationMemoryService` and `LeadJourneySnapshot`.

**Produces:** Each provider receives the same current-turn-first context containing thread memory, journey state, and only relevant current shared facts.

**Status:** Complete. Shared memory remains disabled by default; production fact lookup/capture requires the protected feature flag and secret configuration.

**Interfaces:**
- `AdvisorContext(String currentMessage, ConversationMemoryContext threadMemory, List<CustomerShoppingFact> sharedCustomerFacts, LeadJourneySnapshot leadJourney)`
- `CustomerMemoryService.relevantFacts(ConversationMemoryKey key, String currentMessage, Instant now, int limit): List<CustomerShoppingFact>`
- `ConversationAssistant.respond(ConversationTurnRequest request): AssistantReply` continues as the provider-neutral conversation port.

- [x] **Step 1: Add failing context tests.**

Extend `CustomerMemoryServiceTest` and `ConversationAssistantAdapterTest` to prove:

- Two different provider-account conversation keys for the same participant load the same relevant customer facts but keep their own recent messages and summaries.
- Other tenants/participants receive no facts.
- Expired active facts are omitted; declined model/offer markers are labeled distinctly and never rendered as positive preferences.
- The inbound message is present once and has priority over stored facts/history.
- Shared facts are capped and carry fact type/date/source reference, not raw source text.
- A profile-store outage falls back to thread memory without broadening lookup or failing the turn.

  - **Actions Applied**
    - `ford-ai-service/src/test/java/com/ford/service/conversations/CustomerMemoryServiceTest.java` — cross-account identity links, same-participant fact visibility, tenant isolation, relevance filtering, and caps.
    - `ford-ai-service/src/test/java/com/ford/service/ai/ConversationAssistantAdapterTest.java` — thread/shared/journey context assembly, no duplicate current message, bounded fact section, and outage fail-open.
    - `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationMemoryServiceTest.java` — inbound fact capture occurs only after thread-memory inbound persistence succeeds.
  - **Verification**
    - Initial RED for missing `relevantFacts` API and, separately, profile-store failure escaping the adapter.

- [x] **Step 2: Run context tests and verify failure.**

```bash
./gradlew test --tests 'com.ford.service.conversations.CustomerMemoryServiceTest' --tests 'com.ford.service.conversations.DefaultConversationTurnProcessorTest' --tests 'com.ford.service.ai.ConversationAssistantAdapterTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: new assertions fail because memory context does not contain cross-thread facts.

  - **Actions Applied**
    - Ran the new focused tests before introducing the shared fact provider/context path.
  - **Verification**
    - Confirmed expected missing-method and uncaught-profile-failure red states.

- [x] **Step 3: Extend the provider-neutral context contract.**

Add immutable `AdvisorContext(String currentMessage, ConversationMemoryContext threadMemory, List<CustomerShoppingFact> sharedCustomerFacts, LeadJourneySnapshot leadJourney)`. In `ConversationAssistantAdapter`, create it from `ConversationTurnRequest` and load profile facts using the existing memory key; `providerAccountId` remains in thread-memory queries but is excluded from shared-profile lookup. Include at most three relevant profile facts and 600 serialized characters: exact model/trim or offer matches for a named request; active budget/timeframe only for a current shopping/finance request. This gives graph and legacy workflows the same context assembly. Bound profile results before constructing the context.

Use this record shape and normalize shared facts to an immutable list:

```java
public record AdvisorContext(
        String currentMessage,
        ConversationMemoryContext threadMemory,
        List<CustomerShoppingFact> sharedCustomerFacts,
        LeadJourneySnapshot leadJourney) {}
```

  - **Actions Applied**
    - Added immutable `AdvisorContext` and `SharedCustomerFactProvider`; adapter requests tenant/channel-scoped relevant facts using the memory key, then emits model/offer matches and shopping facts only for relevant current turns.
    - New threads are linked to their tenant-scoped identity without merging conversation IDs or transcript history.
  - **Verification**
    - `CustomerMemoryContextIntegrationTest` uses PostgreSQL and proves two provider-account conversations link to one identity, show only the named declined Bronco, exclude budget for a model-only request, and isolate another tenant.

- [x] **Step 4: Capture explicit facts after accepted inbound persistence.**

In `ConversationMemoryService.recordInbound`, link the identity and capture deterministic explicit facts from the accepted customer message. This keeps the profile current even if the model call later fails. Make the operation idempotent and fail-open after webhook acknowledgement; never store assistant text. Keep thread-scoped `LeadJourneySnapshot` as its existing owner.

  - **Actions Applied**
    - `ConversationMemoryService.recordInbound` invokes `CustomerMemoryService.captureExplicitFacts` only after successful inbound memory persistence; errors are fail-open and log exception class only.
  - **Verification**
    - Unit test asserts the capture callback observes persisted inbound state and receives the current accepted message/receipt provenance.

- [x] **Step 5: Render one canonical context for every model provider.**

Update `ConversationAssistantAdapter` to build one `AdvisorContext` containing the current message and all context layers. Serialize the current request exactly once as the user message; serialize thread memory, journey, and shared facts as system-context sections:

```text
[THREAD MEMORY — RECENT TURNS AND SUMMARY]
<bounded current-thread memory>
[/THREAD MEMORY]
[LEAD JOURNEY — CURRENT THREAD]
<existing lead journey state>
[/LEAD JOURNEY]
[CONFIRMED CUSTOMER SHOPPING FACTS — CROSS-THREAD]
<only relevant active facts and explicit declined markers, with date/source>
[/CONFIRMED CUSTOMER SHOPPING FACTS]
```

Keep labels in Spanish in the customer-facing model prompt. Bound the final serialized context by configured characters/tokens; preserve the current request and newest turns first. Populate `AiExecutionContext.recentAssistantReplies` from outbound messages in thread memory without logging them. Reuse the same `AssistantRequest` for primary DeepSeek and fallback providers; add a routing test asserting both adapters receive identical memory/journey/fact/recent-reply context.

  - **Actions Applied**
    - `ConversationAssistantAdapter` serializes bounded shared facts in Spanish, marks declines as “RECHAZADO; NO OFRECER”, keeps current inbound separate and filters duplicate inbound history, and preserves the existing journey formatter.
    - `AiExecutionContext` and `AssistantRequest` carry immutable recent assistant replies; routing forwards the same request to primary/fallback providers.
  - **Verification**
    - Adapter tests prove thread/shared context, non-duplicated current question, reply extraction, decline labels, and <=600-character shared-fact payload.
    - Provider routing test proves primary and fallback receive equal memory, journey, and recent-reply fields.

- [x] **Step 6: Run unit and integration context tests.**

```bash
./gradlew test --tests 'com.ford.service.conversations.CustomerMemoryServiceTest' --tests 'com.ford.service.conversations.DefaultConversationTurnProcessorTest' --tests 'com.ford.service.ai.ConversationAssistantAdapterTest' --tests 'com.ford.service.ai.SpringAiAssistantServiceTest' --tests 'com.ford.service.ai.ProviderRoutingChatCompletionPortTest' --rerun-tasks --no-build-cache --max-workers=1
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
./gradlew integrationTest --tests 'com.ford.service.conversations.CustomerMemoryContextIntegrationTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: provider parity, context order/bounds, tenant isolation, and fallback behavior pass.

  - **Actions Applied**
    - Ran the complete service unit suite and four focused Testcontainers identity/fact/context/migration integrations.
  - **Verification**
    - Full unit suite: `./gradlew test --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL**.
    - Focused integration suite containing `CustomerMemoryContextIntegrationTest`, `CustomerMemoryFactIntegrationTest`, `CustomerMemoryChangelogIntegrationTest`, and `CustomerMemoryIdentityIntegrationTest` — **BUILD SUCCESSFUL**.

## Task 4: Make Duplicate-Reply Protection Provider-Independent and Always Active

**Consumes:** Current-thread recent assistant messages and assembled advisor context from Task 3.

**Produces:** One repair attempt for a repeated candidate, then no outbound send if repetition remains.

**Interfaces:**
- `AdvisorResponseRepeatPolicy.evaluate(String currentMessage, List<String> recentAssistantReplies, String candidate): Verdict`
- `Verdict(boolean repeated, String reasonCode)`
- `AssistantRequest.withRepairInstruction(String instruction): AssistantRequest`

- [x] **Step 1: Add failing guard tests using synthetic repeat cases.**

Create `AdvisorResponseRepeatPolicyTest` with:

- exact normalization of punctuation/whitespace/diacritics;
- a semantically high-overlap variant;
- a repeated concise answer is allowed when the customer repeats the same question;
- the same generic advisor reply to materially different questions is rejected;
- unrelated short answers are not rejected due to a shared greeting or fixed handoff suffix.
- repair executes at most once whether broad response verification is enabled or disabled; a second repeated result becomes `REVIEW`.

Include a synthetic three-turn case corresponding to the production sequence without any production customer text.

  - **Actions Applied**
    - Added `AdvisorResponseRepeatPolicyTest` and service/processor tests for normalized exact/near repeats, repeated questions, different questions, one repair, review disposition, and final pre-commit withholding.
  - **Verification**
    - Confirmed RED for the missing repeat API and the service-level repeated-reply behavior before implementation.

- [x] **Step 2: Run the guard tests and verify they fail.**

```bash
./gradlew test --tests 'com.ford.service.shared.AdvisorResponseRepeatPolicyTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: missing guard type/test behavior fails.

  - **Actions Applied**
    - Ran focused repeat-policy and turn-service tests before implementing guards.
  - **Verification**
    - Observed expected missing-symbol, repeat-accepted, and optional-quality-policy repeat detections.

- [x] **Step 3: Implement a separate always-on repeat guard.**

Create `AdvisorResponseRepeatPolicy` with deterministic normalized exact comparison and a conservative word/phrase overlap threshold. Compare a candidate with recent assistant replies, excluding the just-accepted current inbound message. Treat greetings/fixed boilerplate separately so a reused sign-off alone does not reject otherwise new content. Return only a reason code; never log candidate text.

Use a pure provider-independent contract:

```java
public Verdict evaluate(String currentMessage, List<String> recentAssistantReplies, String candidate);
public record Verdict(boolean repeated, String reasonCode) {}
```

  - **Actions Applied**
    - Added `AdvisorResponseRepeatPolicy` with punctuation/diacritic normalization, boilerplate-filtered overlap, concise-reply protection, and reason-only verdicts.
  - **Verification**
    - Synthetic policy suite passes exact, near, question-repetition, generic-answer, and shared-greeting cases.

- [x] **Step 4: Apply the guard after provider routing and repair once globally.**

Add `recentAssistantReplies` and optional `repairInstruction` fields to `AiExecutionContext` and `AssistantRequest`; preserve existing constructors and add `AssistantRequest.withRepairInstruction(String)`. In `SpringAiAssistantService`, evaluate `AdvisorResponseRepeatPolicy` after `orchestrator.respond(...)`, using the same user message and structured prior replies. On repeat, call the orchestrator once more with the same context and a repair instruction to answer the current request and advance one step. Re-check; do not request a second repair across another provider. If still repeated, throw `AssistantTurnUnavailableException(REVIEW)` before returning an answer. `SpringAiChatCompletionAdapter` adds the instruction to the provider prompt. Remove repeat checking from `AssistantResponseQualityPolicy.evaluate`; keep its repair-copy helper for the central guard. Keep broader quality/evidence checks controlled by the existing setting; the repeat-only check has no disable flag.

Remove repeated-response detection from `AssistantResponseQualityPolicy.evaluate` and its adapter-level repair branch so response-verification-on and -off configurations cannot produce multiple repeat retries. Keep non-repeat quality/evidence checks on their current configuration path.

  - **Actions Applied**
    - Added immutable recent replies and repair instruction to AI/request context. Central assistant service retries once; adapter carries the repair directive while routing reuses the same provider-neutral request. Removed repeat check from optional quality policy.
  - **Verification**
    - Service test proves exactly two orchestrator calls and REVIEW after a repeated repair result; routing parity and focused AI suites pass.

- [x] **Step 5: Add a final pre-commit guard after response planning.**

In `DefaultConversationTurnProcessor` and the legacy `ConversationWorkflow`, apply `AdvisorResponseRepeatPolicy` to the final planned text before commit/outbox. If it still repeats, raise the existing review/unavailable disposition so no `completeBotTurn`, outbound event, or delivery row is created. Preserve the inbound transcript and bounded retry/review state.

  - **Actions Applied**
    - Added final checks after response planning in both graph and legacy turn paths; repeated final text throws REVIEW before commit/outbox.
  - **Verification**
    - Processor test asserts the commit service is never called for a repeated final response.

- [x] **Step 6: Run focused and complete unit tests.**

```bash
./gradlew test --tests 'com.ford.service.shared.AdvisorResponseRepeatPolicyTest' --tests 'com.ford.service.ai.AssistantResponseQualityPolicyTest' --tests 'com.ford.service.ai.SpringAiAssistantServiceTest' --tests 'com.ford.service.ai.SpringAiChatCompletionAdapterTest' --tests 'com.ford.service.conversations.DefaultConversationTurnProcessorTest' --tests 'com.ford.service.conversations.ConversationWorkflowTest' --rerun-tasks --no-build-cache --max-workers=1
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
```

Expected: repeated candidate triggers one repair; a second duplicate is withheld without outbound commit; ordinary greetings and distinct answers still pass. Full unit suite passes.

  - **Actions Applied**
    - Ran focused repeat/AI/processor tests and full service unit suite.
  - **Verification**
    - `./gradlew test --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL**.

## Task 5: Add Audited Reset/Forget Controls and Protect Disabled Meta Threads

**Consumes:** Existing operator authentication, conversation version/idempotency, identity mapping, and fact store.

**Produces:** Clear operator actions with independent effects; disabled-account threads stay visible as history but cannot send.

**Interfaces:**
- `ConversationMemoryRestartRequest(RestartReason reason)`
- Nested `RestartReason` enum values: `CUSTOMER_REQUEST`, `STALE_CONTEXT`, `OPERATOR_RECOVERY`, `OTHER`, `LEGACY_CLIENT_UNSPECIFIED`.
- `CustomerMemoryForgetService.forget(AuthenticatedOperator operator, UUID tenantId, UUID conversationId, String idempotencyKey): CustomerMemoryForgetReceipt`
- `CustomerMemoryForgetReceipt(UUID conversationId, int removedFactCount, Instant forgottenAt)`

- [x] **Step 1: Add failing restart reason and disabled-binding tests.**

Extend `ConversationMemoryRestartServiceTest`, `ConversationControllerTest`, and command-service tests to assert:

- every new restart stores one reason code, actor, timestamp, and target conversation;
- idempotent replay does not create a second audit event;
- restart preserves transcript and customer facts;
- operator send against a disabled `channel_binding` is refused before append/outbox;
- active binding continues to accept operator messages.

  - **Actions Applied**
    - Restart service/controller tests cover reason, actor audit, and replay; PostgreSQL binding test covers read-only projections and enabled/disabled send behavior.
  - **Verification**
    - Focused restart/controller tests and enabled-vs-disabled binding Testcontainers integration passed.

- [x] **Step 2: Add failing forget-facts authorization/idempotency tests.**

Create `CustomerMemoryForgetServiceTest` and controller tests proving tenant/operator write authorization, lookup via conversation ID, idempotency, removal of active and declined values, advancing `facts_forget_before`, and no transcript deletion.

  - **Actions Applied**
    - Added operator and tenant authorization tests, read-only rejection, tenant-scoped ADMIN endpoint test, and replay idempotency tests. PostgreSQL admin-path test exercises real forget service, JDBC idempotency/key/fact repositories, active/declined deletion, and transcript preservation.
  - **Verification**
    - Focused controller test passed. Admin Testcontainers test initially exposed a missing test-fixture migration 009; fixture now applies it and the test passes.

- [x] **Step 3: Implement restart audit reason and preserve compatibility deliberately.**

Add a validated reason enum (`CUSTOMER_REQUEST`, `STALE_CONTEXT`, `OPERATOR_RECOVERY`, `OTHER`). Store only the reason code, not free-text notes. Update the restart request/OpenAPI contract and idempotency request hash. For pre-existing clients during transition, map an omitted reason to `LEGACY_CLIENT_UNSPECIFIED` and emit a content-free metric; do not silently discard the fact that the reason was omitted. Keep this compatibility path until the operator client sends a reason.

  - **Actions Applied**
    - Added request reason enum, legacy omission mapping, actor/reason/timestamp restart event, and reason-inclusive idempotency hash.
  - **Verification**
    - Restart service test captures emitted reason; focused service/controller/realtime tests pass. OpenAPI source contract remains to be updated.

- [x] **Step 4: Implement the authorized customer-facts forget operation.**

Expose `DELETE /api/v1/conversations/{conversationId}/customer-memory` guarded by `operator:write` or `ADMIN`, tenant resolution, and `Idempotency-Key`. Resolve the linked customer identity from the selected conversation, delete shared fact values and advance the forget boundary transactionally, invalidate profile cache, and return a receipt with counts only. Do not delete messages or other conversations.

  - **Actions Applied**
    - Added tenant-scoped authorized/idempotent forget service, controller route, count-only receipt, identity lookup through the selected conversation, and repository fact deletion/forget boundary.
  - **Verification**
    - Unit tests cover authorized execution and refusal before lookup for a read-only operator. Admin Testcontainers coverage remains outstanding.

- [x] **Step 5: Make disabled-binding conversations read-only.**

Extend `OperatorConversationCommandRepository.CommandTarget` with binding-enabled state by joining `channel_binding` on channel/provider account. Reject operator sends when the binding is missing or disabled before appending `conversation_message` or outbox. Add a `readOnly` field to `ConversationView` for the inbox to render historical threads without altering their transcript/state.

  - **Actions Applied**
    - Binding state now joins on tenant/channel/provider account; command service rejects disabled/missing bindings. `ConversationView.readOnly` is populated from that binding state.
  - **Verification**
    - Disabled-send unit case confirms no append; full PostgreSQL integration suite passes. Explicit disabled-vs-enabled binding database assertions remain outstanding.

- [x] **Step 6: Run focused controller, service, and database tests.**

```bash
./gradlew test --tests 'com.ford.service.conversations.ConversationMemoryRestartServiceTest' --tests 'com.ford.service.conversations.ConversationControllerTest' --tests 'com.ford.service.conversations.CustomerMemoryForgetServiceTest' --tests 'com.ford.service.conversations.CustomerMemoryControllerTest' --tests 'com.ford.service.conversations.ConversationCommandServiceTest' --tests 'com.ford.service.conversations.JdbcOperatorConversationRepositoryTest' --tests 'com.ford.service.config.OpenApiContractTest' --rerun-tasks --no-build-cache --max-workers=1
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
./gradlew integrationTest --tests 'com.ford.service.conversations.CustomerMemoryAdminIntegrationTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: audited restart and forget semantics pass; disabled Meta account creates no operator outbound row/outbox delivery; enabled account still works.

  - **Actions Applied**
    - Ran complete service unit suite, complete PostgreSQL/Testcontainers integration suite, synthetic replay tests, and whitespace check after Task 5 additions.
  - **Verification**
    - `./gradlew test --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL**.
    - `./gradlew integrationTest --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL**.
    - `node --test scripts/ai/advisor-memory-shadow-replay.test.mjs` — **5/5 passed**; `git diff --check` passed.

## Task 6: Build the Private No-Delivery Replay and Rollout Gates

**Consumes:** Tasks 1–5 and the three recent production turns already retrieved through read-only emme-2 access.

**Produces:** Repeat/fact relevance evidence without committing or logging customer content and without outbound delivery.

**Interfaces:**
- Input JSON contains one `scenarioId`, `systemContext`, `recentTurns[]`, and `replayTurns[]`; it contains no phone/account/conversation identifiers.
- Output JSON contains model name, turn count, exact/near-repeat counts, answer-coverage labels, latency percentiles, and cost estimate only.
- Nested `AggregateReplayReport` record contains only those aggregate fields.
- `runReplay({snapshotPath, baseUrl, model, apiKey, fetchImpl}): AggregateReplayReport`

The private input shape is:

```json
{"scenarioId":"private-local-id","systemContext":"...","recentTurns":[{"role":"user","content":"..."},{"role":"assistant","content":"..."}],"replayTurns":[{"content":"..."}]}
```

The persisted report contains aggregates only, for example:

```json
{"model":"deepseek-flash","turnCount":3,"exactRepeatCount":2,"nearRepeatCount":2,"answeredCurrentRequestCount":1,"p50LatencyMs":900,"p95LatencyMs":1400,"estimatedCostUsd":0.01}
```

- [x] **Step 1: Add synthetic runner contract tests.**

Test that the runner accepts a local snapshot path and explicit base URL/model/key environment names; rejects missing or malformed turns; never initializes a delivery client; and writes only aggregate counts, similarity/rejection reason codes, latency, and model names. Tests use synthetic conversations only.

  - **Actions Applied**
    - Added synthetic runner tests for accepted fixture, malformed turns, missing credentials, aggregate-only output, zero delivery-client construction, and sequential model comparison.
  - **Verification**
    - `node --test scripts/ai/advisor-memory-shadow-replay.test.mjs` — 4 passing.

- [x] **Step 2: Implement the local replay runner.**

Use the existing OpenAI-compatible HTTP approach from `scripts/ai/openrouter-chat-eval.mjs`, parameterized for the configured DeepSeek endpoint/model and a candidate fallback. Load private snapshot data from a caller-specified path; do not place that file under source control. Replay the three repeated general-advice turns against the same preceding 20 messages and lead state. Do not expose API keys in command-line arguments, report files, or logs. Write detailed raw replies only to process memory for similarity scoring and discard them on exit; output aggregate scores only.

  - **Actions Applied**
    - Added caller-path OpenAI-compatible local replay runner; key is env-only; raw content stays in process memory; stdout returns aggregates only. No delivery adapter exists in the runner.
  - **Verification**
    - Synthetic runner contract tests pass; no private or production snapshot was read.

- [x] **Step 3: Compare DeepSeek and fallback with the same context.**

Run candidate models sequentially with identical `AdvisorContext`; measure exact-repeat rate, semantic overlap, whether the current question was answered, topic progression, unsupported-claim count, p50/p95 latency, and provider cost. A model change is not accepted based on fluency alone; the deterministic repeat guard remains required for every model.

  - **Actions Applied**
    - Replay turns may provide synthetic `expectedTopicTokens` and `unsupportedClaimPatterns`; runner scores these into aggregate `topicProgressionCount` and `unsupportedClaimCount`, alongside existing repeat, coverage, latency, and cost metrics.
    - Comparison remains sequential and uses the same snapshot/context for each candidate. Pattern-based unsupported-claim counts are annotation-driven, not a general factuality detector.
  - **Verification**
    - Added synthetic metric test; verified RED before implementation, then `node --test scripts/ai/advisor-memory-shadow-replay.test.mjs` — **5/5 passed**.
    - Aggregate report contains no matched terms or response text. No private snapshot or live model request used.

- [x] **Step 4: Run unit then integration gates.**

Run from the repository root:

```bash
node --test scripts/ai/advisor-memory-shadow-replay.test.mjs
```

Run from `ford-ai-service`:

```bash
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
./gradlew integrationTest --rerun-tasks --no-build-cache --max-workers=1
```

Expected: synthetic replay tests, Java unit tests, and PostgreSQL integration tests pass before any image build or production rollout.

  - **Actions Applied**
    - Ran node synthetic tests, complete Java unit suite, and complete Testcontainers integration suite in order.
  - **Verification**
    - Node: 4/4 pass. Gradle unit and full integration tasks both **BUILD SUCCESSFUL**.

- [ ] **Step 5: Complete protected production prerequisites.**

Provision the dedicated HMAC secret through the approved OCI Vault process and wire only its secret reference into the protected Service environment. Verify key presence without printing the value. Add the new migration and feature variables to release checks. Keep shared-profile writes disabled until protected migration, backup, HMAC-key verification, and read-only identity-link audit pass.

  - **Actions Applied**
    - Added local `CustomerMemoryReleaseConfigurationTest` checks for migration 028 registration, production feature default `false`, and externally supplied current/previous HMAC values.
    - User approved protected work on 2026-10-20. Checked-in `.github/workflows/release.yml` deploys from protected `main` through readiness gates and a pinned shared OCI blue-green action; `.github/workflows/terraform-runtime-vault.yml` uses protected GitHub OCI API-key credentials, reviewed Terraform plan artifact, and separate apply approval.
    - Found release wiring gap: release workflow, app template, and Terraform allowlist lack the customer-memory HMAC OCID; the deployment action used by release.yml is external and pinned, so its input contract needs a separate reviewed update. Feature remains default-off.
    - Worktree lacks ignored deployment config/mapping and private snapshot handoff. Default-profile and security-token OCI read-only checks failed; named API-key OCI read-only succeeded but was not used to bypass the Vault task's security-token auth. No secret was created, fetched, or printed; no customer-data search was run.
  - **Verification**
    - `./gradlew test --tests 'com.ford.service.conversations.CustomerMemoryReleaseConfigurationTest' --rerun-tasks --no-build-cache --max-workers=1` — **BUILD SUCCESSFUL**.
    - Local Vault-task preflight: **BLOCKED** (missing ignored config/mapping and required security-token auth). Protected Terraform workflow exists, but no plan/apply was dispatched. Secret provisioning, production wiring, migration, backup, identity-link audit, and private replay remain **not performed**.

- [ ] **Step 6: Replay the private production snapshot and review results.**

Run the local replay using the already retrieved recent turns, with delivery disabled and no production DB writes. Confirm the repeat guard rejects the repeated candidate, repair responds to each distinct question, provider fallback receives identical context, and the report contains no message text, phone, conversation ID, prompt, or key. Delete the private snapshot and raw runner output after review under the approved local-data handling process.

## Completion Gate

- [ ] Unit tests pass before integration tests.
- [ ] Integration tests pass only against a dedicated `_test` database.
- [ ] The private replay has zero delivery calls and emits aggregate-only output.
- [ ] Two provider-account threads remain separate and share only approved profile facts.
- [ ] Declined model/trim/offer markers persist until explicit reopen or forget; active facts expire at 90 days.
- [ ] Restart reason audit works; thread restart does not erase profile facts or transcript.
- [ ] Disabled Meta bindings make historical conversations read-only.
- [ ] No production feature enablement/deployment occurs before protected HMAC/Vault and rollout approvals.

## Execution Checkpoint — 2026-10-20

- Task 1 identity/fingerprint/schema/backfill primitives implemented in the isolated `feat/catalog-full-retrieval` worktree.
- Production customer-memory feature flag remains `false`; no HMAC secret provisioned; no production database or deployment touched.
- Focused unit + Modulith architecture tests: **PASS**.
- Full service unit suite after Task 3: **PASS**.
- Focused PostgreSQL Testcontainers identity, fact lifecycle, migration, and cross-account context tests: **PASS**.
- Tasks 1–5 complete in the isolated worktree with controller/admin database/API-contract coverage. Task 6 synthetic runner and local gates are implemented; live model comparison and private production replay remain blocked and were not attempted.
- Task 4 repeat guard: central one-repair retry plus final pre-commit check; optional quality verification no longer owns repeat detection.
- Task 5: restart reason/actor audit event, idempotent customer-fact forget API/service, tenant-scoped ADMIN/operator authorization, and disabled-binding read-only command/query behavior are covered. PostgreSQL test exercises real JDBC idempotency and fact repositories while preserving transcript.
- Task 6: aggregate-only synthetic replay measures repeat, answer coverage, annotated topic progression/unsupported claims, latency, and cost; candidate comparison is sequential on the same fixture. No production snapshot, API key, database, or delivery path was accessed.
- Task 6 protected gate: local release configuration test asserts migration 028 and disabled-by-default secret-backed profile configuration. Review found the checked-in production release and Terraform Vault workflows; customer-memory HMAC OCID wiring is absent from release validation, app template, shared-action input chain, and runtime Vault policy.
- Approval received 2026-10-20 for protected work. Release path requires protected `main`, verified tag/source SHA, CI, immutable image digest/attestation, production readiness variables, approval, pre-deploy backup, blue-green health/rollback. Vault identity changes use a separate reviewed Terraform plan/apply workflow. No production workflow was dispatched. OCI local Vault task still lacks required config/security-token access; the private snapshot handoff remains unavailable; no customer-data search was done.
- Current verification: full Java unit suite **PASS**; full integration suite **PASS**; synthetic replay Node suite **PASS** (5 tests); `git diff --check` pass.

## Plan Self-Review

- Identity and account-change continuity: Task 1 keeps thread IDs separate and maps same-tenant WhatsApp participants through versioned HMAC identities.
- Explicit fact extraction, update, decline, 90-day expiry, and forget boundary: Task 2.
- Bounded current-turn-first context, lead journey, same facts for DeepSeek/fallback, and profile-store failure behavior: Task 3.
- Always-on repeat guard, one repair budget, final pre-commit check, and no outbound delivery on a repeated result: Task 4.
- Restart reason audit, forget-facts endpoint, and disabled-Meta historical thread read-only behavior: Task 5.
- Private no-delivery production replay, model comparison, unit/integration order, secret provisioning, and protected rollout: Task 6.
- Transcript erasure is intentionally excluded from this implementation plan; it requires a separate approved retention/privacy policy and plan.

The incomplete-step scan is clear. Existing test class names were checked against the Service test tree; newly introduced test classes are explicitly listed as files to create.
