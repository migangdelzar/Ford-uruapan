# Ford Catalog Callbacks and Full Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every Service-owned read-only catalog callback reachable, add evaluated BGE-M3 retrieval, deliver concise evidence-only Spanish replies with silent bounded retries, provision the SELECT-only production reader, then enable callbacks through protected releases.

**Architecture:** Ford-scraping remains the sole catalog writer and adds one versioned `ford_read` document/embedding view. Ford Service uses a separate `ford_catalog_reader` datasource, generates BGE-M3 query vectors through the existing protected Ollama Access route, and fuses structured-filtered vector/full-text/trigram candidates with the existing RRF utility. Customer replies use only requested, current-turn verified facts; temporary failures retry silently, and unresolved turns do not commit customer messages. The default-off callback flag stays false until the read-only reader, retrieval quality, grounding, conversation behavior, and production smoke gates pass.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL 16/pgvector, Node 22, Vitest, Gradle, OCI Vault, protected GitHub Actions.

The user approved completing full semantic retrieval in addition to structured callbacks. Prior Node/MCP byte-for-byte parity remains retired. Production currently has no callback enablement or Ollama Access secret mapping. The latest Service `v0.1.26` changed tests/docs only. The earlier scheduler `v0.1.13` attempt failed during OCI X509 federation before migrations. See the source-review correction below for the current action and Ford-scraping release branch state.

**Plan correction from source review:** Use Ford-scraping `origin/main` (`5227f5e`) for the migration/release work; it contains migrations 022–025 and the release workflow pins action SHA `a878ad1ba1dd4a25ef814ea4b69ac817ae32165a`. `origin/HEAD` points to `chore/platform-secret-management` (`1bd7990`), a separate branch missing those views, so it is not a valid base. The action clone's `origin/main` (`36b6795`) contains reader provisioning, while the scheduler-container X509 POST probe is on `origin/fix/scheduler-x509-post-probe` (`c64992f`). Fetching the `a878...` SHA from that local action remote failed; verify the correct upstream object and its exact contents before relying on the pin or releasing.

## Global Constraints

- Ford-scraping remains the only owner of the `ford` schema, migrations, ingestion, and catalog writes.
- Use the existing database password value with `ford_catalog_reader`; create no second database password secret.
- `ford_catalog_reader` receives only `CONNECT` on `ford_service`, `USAGE` on `ford_read`, and `SELECT` on versioned read views; remove inherited membership and base-schema/write access.
- Keep `FORD_CATALOG_TOOLS_ENABLED=false` until the reader, Ollama Access route, callback policy, retrieval evaluation, grounding checks, and silent-retry/customer-copy tests pass.
- Keep BGE-M3 model name and 1,024 dimensions aligned with stored embeddings. Never claim semantic readiness for a lexical fallback or mismatched model.
- Keep tool/provider failures invisible to customers; allow one silent immediate retry for transient catalog-tool failures, keep existing provider failover, then use bounded event recovery. Do not commit an outbound reply when no current evidence can answer the request.
- Keep price/promotion replies short and source-grounded; do not use stale memory for volatile facts or append hours/holiday details unless asked.
- Do not restore Node MCP or require Node/MCP output parity; that gate was retired by user decision.
- Never expose secrets or Ollama publicly. Tests use only disposable `*_test` databases; never point integration tests at production.
- Keep reranking disabled unless reviewed evaluation demonstrates a gain within measured latency/resource cost.

---

## File Structure

### Ford Service — `/private/tmp/ford-uruapan-release`

- Modify `ford-ai-service/src/main/java/com/ford/service/ai/application/DecisionPolicy.java` and `DecisionPolicyTest.java` — allow all supported catalog tools only for appropriate read-only intent families.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`, `ToolFailureDiagnostics.java`, and their tests — recognize every callback name without leaking internal tool names.
- Modify `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogPort.java`, `VehicleSearchQuery.java`, `OfferQuery.java`, and add immutable retrieval DTOs — carry optional BGE-M3 query vectors and return ranked evidence with truthful semantic state.
- Add `ford-ai-service/src/main/java/com/ford/service/catalog/application/CatalogRetrievalService.java` and `CatalogEmbeddingProperties.java` — orchestrate embedding, candidate lists, RRF, and lexical fallback without changing the global embedding model.
- Modify `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcCatalogAdapter.java` — parameterized candidate queries against versioned `ford_read` views; structured results remain authoritative.
- Add `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/in/health/CatalogReadinessHealthIndicator.java` — bounded, content-free SELECT health check only when callbacks are enabled.
- Add `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogQueryEmbedder.java` and `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/OllamaCatalogQueryEmbedder.java` — Spring AI request explicitly selects the stored BGE-M3 model and verifies a 1,024-element vector.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java` and `SpringAiConfiguration.java` — embed free-text searches, preserve exact filters, use RRF and report semantic state.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogToolParityIntegrationTest.java` and add `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogSemanticRetrievalTest.java` and `OllamaCatalogQueryEmbedderTest.java` — validate vector contracts, fallback, structured filters, and three-list fusion.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java`, `ToolResultTracker.java`, `SpringAiAssistantService.java`, and `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java` — distinguish transient/permanent failures, preserve successful current-turn facts, and propagate a typed no-reply disposition.
- Modify `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md` — require exact callback guidance, truthful semantic fallback, short direct replies, no stale price/promotion facts, and no user-visible failure text when the application retries a failed source.
- Modify `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java` and `DealershipResponsePlanner.java` — keep retryable turns uncommitted, hold permanent failures for review, and limit hours notices to explicit hours/follow-up requests. Verify existing `ConversationWorkflow.java` and `ConversationPublicationRecovery.java` behavior; change neither unless a failing regression test proves it necessary.
- Modify `ford-ai-service/src/main/java/com/ford/service/ai/application/CatalogFactResponseRenderer.java` and these templates — `ford-ai-service/src/main/resources/response-templates/vehicle-price.md`, `promotion.md`, `price-and-promotion.md`, `price-unconfirmed.md`, and `promotion-unconfirmed.md` — render only requested facts, omit failed fields, and keep successful no-result wording direct.
- Add `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantTurnUnavailableException.java` — carry only `RETRY` or `REVIEW` disposition across the AI/conversations module boundary; never carry customer text, tool payloads, or raw exceptions.
- Modify `ford-ai-service/src/main/java/com/ford/service/knowledge/application/ReciprocalRankFusion.java` — add deterministic N-list fusion while retaining the existing two-list API; export only the utility type if the Modulith boundary test requires it.
- Add `ford-ai-service/src/test/resources/catalog/catalog-retrieval-eval.json` and `CatalogRetrievalEvaluationTest.java` — 100 reviewed Spanish cases seeded from Ford-scraping's existing 198-row QA corpus, with expected routes/documents/facts, exact and no-evidence cases, and retrieval/grounding metrics.
- Modify `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogToolParityIntegrationTest.java`, `CatalogDataSourceConfigurationTest.java`, and relevant Spring AI tests — synthetic PostgreSQL vectors, datasource and callback registration, fallback, and authorization evidence.
- Modify `deployment/ansible/roles/app/templates/app.env.j2` and `.github/workflows/release.yml` only to pass verified non-secret Ollama URL/model/client ID and an OCI Vault secret OCID, never a secret value.
- Modify `Ford-oci-deployment-action/.github/actions/oci-blue-green/action.yml`, `deploy.sh`, `deployment/ansible/roles/app/templates/app.env.j2`, and `tests/test_action_package_contract.py` to pass the verified Ollama URL/model/client ID and secret OCID; port the scheduler-container X509 POST probe and regression test from `origin/fix/scheduler-x509-post-probe` onto current action `origin/main`, then pin the reviewed merged commit in release workflows.
- If the Ollama Access secret is not already covered by the Service runtime Vault policy, modify `deployment/terraform/runtime-vault-identity.tf` and the policy contract tests to add exactly one named-secret read grant, then run the protected plan/approval/apply workflow.

### Shared OCI deployment action — `/private/tmp/ford-oci-mia-catalog`

- Branch from current shared-action `origin/main` for the Ollama endpoint/model/client-ID and secret-OCID inputs; do not edit the user's main action checkout. Review the existing role/X509 contract and update Ford-scraping's stale action pin after the new action passes tests.
- Add those inputs to `action.yml`, pass them through `deploy.sh`, and render `FORD_AI_LOCAL_BASE_URL`, `FORD_CATALOG_EMBEDDING_MODEL`, `FORD_AI_OLLAMA_ACCESS_CLIENT_ID`, and `FORD_VAULT_SECRET_OCID_FORD_AI_OLLAMA_ACCESS_CLIENT_SECRET` in the app environment template.
- The Cloudflare Access secret value stays in OCI Vault. If no existing token/Vault secret is found, stop and request approved token provisioning; do not use the Pages API token or expose Ollama publicly.

### Ford-scraping — `/private/tmp/ford-mia-phase1-mcp` on branch `feat/catalog-embedding-read-view-main` based on `origin/main`

- Create `db/migrations/026_catalog_documents_v1.sql` — security-barrier view projecting stable document key/context, semantic content, search vector, embedding/status/model tag, exact source URL, and observation timestamp.
- Modify `tests/unit/catalog-read-views.test.ts` — assert the new migration is additive, read-only in shape, source-backed, and filters/labels embedding state accurately.
- Modify `tests/integration/catalog-read-views.test.ts` — seed a disposable BGE-M3 1,024-dimensional vector and verify the read view returns only the matching ready row with exact selectors and source metadata.
- Current shared-action main already contains reader provisioning, but not the X509 POST-probe fix; carry that fix forward and update Ford-scraping's stale action pin only after action tests pass.

### Release evidence

- Ford-scraping next patch after `v0.1.13`: merge migration and reviewed shared-action pin changes to `main`, run tagged CI, release scheduler first through the protected scheduler workflow, then prove role grants and the one-scheduler invariant.
- Ford Service next patch after `v0.1.26`: tag only the merged Service implementation, keep the flag false until all previous gates pass, then set the three protected catalog variables and enable through the protected Service release.
- Use the protected Deployment Connectivity Preflight after both releases; preserve backups, previous digests, Cloudflared/Ollama, and the shared volume.

### Task 1: Fix callback routing and fail-closed readiness

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DecisionPolicy.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/DecisionPolicyTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/AssistantResponseQualityPolicy.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/AssistantResponseQualityPolicyTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/ToolFailureDiagnostics.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/ai/ToolFailureDiagnosticsTest.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/in/health/CatalogReadinessHealthIndicator.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogReadinessHealthIndicatorTest.java`

**Interfaces:**
- `DecisionPolicy.allowedToolNames(ConversationDecision, Set<String>)` remains the sole family allowlist.
- Vehicle detail family must allow `search_vehicles`, `get_vehicle`, `compare_vehicles`, `get_vehicle_provenance`, `get_page_links`, `compare_trims`, `compare_model_years`, and `get_variant_comparisons`, while preserving the existing `vehicle_snapshot_search` route.
- Offers family must allow `search_offers`, `search_vehicle_and_offers`, `list_promotions`, and `get_catalog_status`.
- Inventory family must allow `search_vehicles` and `search_vehicle_and_offers`; no read family gains write, finance-approval, or appointment operations.
- Health indicator is absent while `ford.catalog.tools.enabled=false`; when true, it runs a bounded read against `ford_read.vehicle_search_v1`, returns only status/aggregate details, and fails health when read access is missing.

- [x] **Step 1: Add failing decision-policy coverage for missing callback names**

```java
@Test
void exposesEveryCatalogComparisonAndSourceToolForVehicleDetails() {
    assertThat(policy.allowedToolNames(decision(ToolFamily.VEHICLE_DETAILS), Set.of(
            "search_vehicles", "get_vehicle", "compare_vehicles", "get_vehicle_provenance",
            "get_page_links", "compare_trims", "compare_model_years", "get_variant_comparisons",
            "search_offers")))
            .containsExactlyInAnyOrder("search_vehicles", "get_vehicle", "compare_vehicles",
                    "get_vehicle_provenance", "get_page_links", "compare_trims",
                    "compare_model_years", "get_variant_comparisons");
}
```

- [x] **Step 2: Run the focused policy test and confirm it fails on the four missing names**

Run from `ford-ai-service`:

```bash
./gradlew test --tests 'com.ford.service.ai.DecisionPolicyTest.exposesEveryCatalogComparisonAndSourceToolForVehicleDetails'
```

Expected: FAIL because `get_page_links`, `compare_trims`, `compare_model_years`, and `get_variant_comparisons` are absent from the vehicle-detail allowlist.

- [x] **Step 3: Add only the callback-family allowlist entries and route tests**

Add only missing callback names; preserve existing `vehicle_snapshot_search`, `get_vehicle`, and `compare_vehicles` permissions in their current families. Add `get_page_links` to the appropriate knowledge family, keep offer callbacks in `OFFERS`, and add tests for all 12 catalog callbacks across supported families plus a `RulesConversationDecisionAdapter` test proving comparison/model questions route to a family that can call comparison callbacks.

Target family mapping:

```java
private static final Map<ToolFamily, Set<String>> TOOL_NAMES = Map.of(
        ToolFamily.VEHICLE_SEARCH, Set.of("search_vehicles", "search_vehicle_and_offers", "get_vehicle",
                "compare_vehicles", "vehicle_snapshot_search"),
        ToolFamily.VEHICLE_DETAILS, Set.of("search_vehicles", "get_vehicle", "compare_vehicles",
                "get_vehicle_provenance", "get_page_links", "compare_trims", "compare_model_years",
                "get_variant_comparisons", "vehicle_snapshot_search"),
        ToolFamily.OFFERS, Set.of("search_offers", "search_vehicle_and_offers", "list_promotions",
                "get_catalog_status"),
        ToolFamily.KNOWLEDGE_SEARCH, Set.of("approved_knowledge_search", "search_vehicles", "get_vehicle",
                "get_vehicle_provenance", "get_page_links", "vehicle_snapshot_search"),
        ToolFamily.FINANCE_ESTIMATE, Set.of("finance_estimate"),
        ToolFamily.APPOINTMENT, Set.of());
```

- [x] **Step 4: Prevent callback names from leaking and preserve catalog diagnostic labels**

Ensure all 12 registered callback names are covered by `AssistantResponseQualityPolicy.INTERNAL_MARKERS`, adding only missing names. Extend `ToolFailureDiagnostics.toolFamily` so `compare_trims`, `compare_model_years`, `get_variant_comparisons`, and `get_page_links` report `catalog`; add one test per family. Keep payloads and secrets out of diagnostics.

- [x] **Step 5: Add fail-closed catalog readiness indicator**

Implement `CatalogReadinessHealthIndicator` with a `CatalogPort` dependency and conditional registration on `ford.catalog.tools.enabled=true`. It must call `catalog.status()`, return UP with only aggregate counts, and convert datasource/read-view exceptions into DOWN without exposing SQL, data rows, or credentials. Do not claim semantic readiness from aggregate catalog status. Test disabled-bean absence, successful read, and datasource failure.

- [x] **Step 6: Run focused and full unit tests**

```bash
./gradlew test --tests 'com.ford.service.ai.DecisionPolicyTest' --tests 'com.ford.service.ai.AssistantResponseQualityPolicyTest' --tests 'com.ford.service.ai.ToolFailureDiagnosticsTest' --tests 'com.ford.service.catalog.CatalogReadinessHealthIndicatorTest'
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
```

Expected: focused tests and full unit suite pass; no production flag or remote resource changes.

### Task 2: Concise replies and silent failure recovery

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java` and `ToolResultTracker.java`.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/ObservedToolCallback.java`, `ToolFailureDiagnostics.java`, `SpringAiChatCompletionAdapter.java`, `ProviderRoutingChatCompletionPort.java`, `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`, and `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java`.
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/api/AssistantTurnUnavailableException.java` with nested `Disposition { RETRY, REVIEW }` and a content-free constructor.
- Modify: `ford-ai-service/src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java` and `DealershipResponsePlanner.java`; verify `ConversationWorkflow.java` and `ConversationPublicationRecovery.java` retain their existing durable retry behavior and five-attempt limit.
- Modify: `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md` and repository-root `docs/operations/catalog-read-only-access.md` with concise customer copy, no-error messaging, and retry/review operations.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/CatalogFactResponseRenderer.java` and `ford-ai-service/src/main/resources/response-templates/vehicle-price.md`, `promotion.md`, `price-and-promotion.md`, `price-unconfirmed.md`, and `promotion-unconfirmed.md`.
- Tests: `ToolAuthorizationPolicyTest.java`, `ObservedToolCallbackTest.java`, `ProviderFailureTest.java`, `ProviderRoutingChatCompletionPortTest.java`, `SpringAiChatCompletionAdapterTest.java`, `SpringAiAssistantServiceTest.java`, `CatalogFactResponseRendererTest.java`, `SystemPromptServiceTest.java`, `ConversationAssistantAdapterTest.java`, `DealershipResponsePlannerTest.java`, `DefaultConversationTurnProcessorTest.java`, and `ConversationPublicationRecoveryTest.java`.

**Interfaces:**
- `AssistantTurnUnavailableException.disposition()` returns only `RETRY` or `REVIEW`; its message and logs contain no customer text, tool arguments, payloads, or credentials.
- `ToolResultTracker` records successful payloads separately from failed tool names and failure disposition; `hasReturnedResult()` remains true only for nonblank successful payloads.
- A model may call each tool once per generation. `ObservedToolCallback` may run a catalog callback at most twice only for `timeout`, `network`, `rate_limited`, or `source_unavailable`; cap the immediate wait at one second, honor only shorter retry-after values, and defer longer waits to durable recovery. Authentication and invalid-request errors do not receive the immediate retry. Keep current provider failover unchanged.
- When provider failover exhausts transient providers for a volatile-information request, `ProviderRoutingChatCompletionPort` throws `AssistantTurnUnavailableException(RETRY)`; no provider- or source-failure template is sent. Missing eligible providers and permanent provider errors use `REVIEW`.
- `CatalogFactResponseRenderer.render(userMessage, successfulPayloads, failedToolNames)` returns only requested facts with current-turn source evidence. On partial success it omits failed fields. A successful explicit zero-result query may return a concise grounded no-result sentence. If no verified answer is available after failure, it returns empty so the caller can defer the turn.
- `DefaultConversationTurnProcessor` does not call `ConversationTurnCommitService.commit` for unavailable turns. `RETRY` propagates to durable event recovery; `REVIEW` leaves the inbound turn uncommitted and stops automatic retries. Existing publication recovery remains bounded to five completion attempts.

Public API contract:

```java
public final class AssistantTurnUnavailableException extends RuntimeException {
    public enum Disposition { RETRY, REVIEW }
    private final Disposition disposition;

    public AssistantTurnUnavailableException(Disposition disposition) {
        super("Assistant turn unavailable");
        this.disposition = java.util.Objects.requireNonNull(disposition);
    }

    public Disposition disposition() { return disposition; }
}
```

- [x] **Step 1: Add failing tests for silent callback retry and failure classification**

Extend `ObservedToolCallbackTest` with one delegate that throws a `SocketTimeoutException` once then returns a known payload; expect two delegate calls and only the successful payload. Add a 403 delegate and an invalid-request delegate; expect one call and `Disposition.REVIEW`. Extend `ToolAuthorizationPolicyTest` to prove that a typed unavailable exception is recorded and rethrown, not converted to `READ_ONLY_SOURCE_UNAVAILABLE`. Change the provider-exhaustion test to expect a typed retry disposition instead of `VOLATILE_SOURCE_UNAVAILABLE` text.

- [x] **Step 2: Run the focused retry tests and confirm the expected failures**

Run from `ford-ai-service`:

```bash
./gradlew test --tests 'com.ford.service.ai.ObservedToolCallbackTest' --tests 'com.ford.service.ai.ToolAuthorizationPolicyTest' --tests 'com.ford.service.ai.ProviderRoutingChatCompletionPortTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: FAIL because the callback currently logs and rethrows once, `ToolAuthorizationPolicy` catches that exception and returns the model-visible unavailable sentinel, and the provider router returns customer-facing unavailable text after candidate exhaustion.

- [x] **Step 3: Add the content-free typed disposition and bounded transient retry**

Add `AssistantTurnUnavailableException` under `ai.api`, use `ToolFailureDiagnostics` categories for the retry allowlist, and implement at most one immediate retry for timeout/network/rate-limit/source-unavailable failures. Preserve a single model-initiated tool call; do not retry authentication, authorization, or invalid-request failures. Record safe disposition in `ToolResultTracker` and rethrow the typed exception after the final failure. Use a bounded delay no longer than one second; defer longer retry-after values to the existing background retry. Make provider exhaustion for volatile requests throw the typed disposition rather than return unavailable text.

- [x] **Step 4: Add failing tests for partial evidence, empty results, and no-response turns**

Extend `CatalogFactResponseRendererTest` to prove a combined price/promotion request renders only the successful verified field when the other tool failed; an explicit empty successful promotion result renders a short no-result statement; failed calls with no successful payload return `Optional.empty()` rather than an unconfirmed template. Add adapter/service/provider-router tests that a retryable failure propagates and never becomes `VOLATILE_SOURCE_UNAVAILABLE` or `HumanHandoffPolicy.BOT_FALLBACK` when no verified fact exists.

- [x] **Step 5: Implement evidence-only rendering and propagate retry disposition**

Pass the failed tool-name set to `CatalogFactResponseRenderer`; exclude failed fields, use a direct grounded no-result sentence only for a successful empty payload, and include at most one source link per requested fact. In `SpringAiChatCompletionAdapter`, return deterministic verified partial answers before handling failures; otherwise propagate `AssistantTurnUnavailableException`. In `ProviderRoutingChatCompletionPort`, preserve existing provider failover but replace terminal volatile-data failure text with `RETRY` or `REVIEW`. In `SpringAiAssistantService`, propagate typed dispositions and classify only unwrapped final provider failures; never send the generic fallback for those failures.

- [x] **Step 6: Add failing tests for conversation commit/recovery and irrelevant hours text**

Extend `DefaultConversationTurnProcessorTest` to verify `RETRY` never commits an outbound turn and `REVIEW` leaves the inbound turn unresolved without a customer message. Extend `ConversationPublicationRecoveryTest` to verify the five-attempt cap. Extend `DealershipResponsePlannerTest` so catalog replies, greetings, and promotions receive no after-hours/holiday suffix, while explicit hours questions still receive the configured notice.

- [x] **Step 7: Implement pending-turn handling and asked-only hours notice**

Make the processor propagate `RETRY` to the existing event retry path and hold `REVIEW` without committing an outbound message. Change `DealershipResponsePlanner.apply` to append hours details only for explicit hours/special-hours or follow-up requests; preserve the existing honest schedule data when those details are requested. Update the prompt's source-failure guidance so it never asks the model to answer with stale/general substitute facts when a volatile lookup failed.

- [x] **Step 8: Tighten reply templates and verify the focused/full unit suites**

Shorten price/promotion templates to requested facts, currency, validity/material conditions, and at most one official source URL. Run:

```bash
./gradlew test --tests 'com.ford.service.ai.ToolAuthorizationPolicyTest' --tests 'com.ford.service.ai.ObservedToolCallbackTest' --tests 'com.ford.service.ai.ProviderFailureTest' --tests 'com.ford.service.ai.ProviderRoutingChatCompletionPortTest' --tests 'com.ford.service.ai.SpringAiChatCompletionAdapterTest' --tests 'com.ford.service.ai.SpringAiAssistantServiceTest' --tests 'com.ford.service.ai.CatalogFactResponseRendererTest' --tests 'com.ford.service.ai.SystemPromptServiceTest' --tests 'com.ford.service.ai.ConversationAssistantAdapterTest' --tests 'com.ford.service.conversations.application.DealershipResponsePlannerTest' --tests 'com.ford.service.conversations.DefaultConversationTurnProcessorTest' --tests 'com.ford.service.conversations.application.ConversationPublicationRecoveryTest' --rerun-tasks --no-build-cache --max-workers=1
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
```

Expected: transient failures retry once with no customer-facing error text; no verified data means no outbound commit; partial data remains answerable; permanent failures stop automatic retry; no unrelated office-hours detail is appended.

### Task 3: Add the versioned BGE-M3 document/embedding read view

**Files:**
- Create: `db/migrations/026_catalog_documents_v1.sql` in Ford-scraping.
- Modify: `tests/unit/catalog-read-views.test.ts` in Ford-scraping.
- Modify: `tests/integration/catalog-read-views.test.ts` in Ford-scraping.
- Read only: `db/migrations/002_schema.sql`, `008_offers.sql`, `017_semantic_document_content.sql`, and `022_mia_catalog_read_views.sql`.

**Interfaces:**
- `ford_read.catalog_documents_v1` columns: `document_id`, `model_id`, `model_year_id`, `variant_id`, `offer_id`, `document_type`, `title`, `semantic_content`, `search_vector`, `embedding`, `embedding_model`, `embedding_status`, `source_url`, and `observed_at`.
- Project only documents with nonblank semantic content (falling back to `content` for lexical retrieval) and source URL/crawl timestamp; left-join embeddings so pending, incompatible, and missing embedding rows remain explicit for truthful fallback.
- Preserve the exact source URL and crawl timestamp; do not expose source credentials or a new writer surface.

- [x] **Step 1: Add failing migration-contract tests**

In `catalog-read-views.test.ts`, read migration `026_catalog_documents_v1.sql` and assert it creates the named `security_barrier` view; includes the declared source, selector, semantic-content, search-vector, model/status, and vector fields; and contains no DML or role grants.

- [x] **Step 2: Run the focused unit contract and verify it fails because migration 026 is absent**

Run from `/private/tmp/ford-mia-phase1-mcp`:

```bash
npx vitest run tests/unit/catalog-read-views.test.ts
```

Expected: FAIL because `026_catalog_documents_v1.sql` does not exist yet.

- [x] **Step 3: Write the additive security-barrier view**

Use the schema from the authoritative migrations. Join source-backed `ford.documents` to `ford.pages`, left-join optional `ford.document_embeddings` by `document_id`, and expose stable document/model/year/variant/offer keys. Fall back to `content` when curated semantic content is blank; retain `d.search_vector` and expose vector model/status as separate fields. Require a source URL and crawl timestamp. Do not modify applied migrations 001-025.

View contract:

```sql
CREATE OR REPLACE VIEW ford_read.catalog_documents_v1
WITH (security_barrier = true)
AS
SELECT d.id AS document_id,
       d.document_key,
       d.model_id,
       d.model_year_id,
       d.variant_id,
       d.offer_id,
       d.document_type,
       d.title,
       coalesce(d.semantic_content, d.content) AS semantic_content,
       d.search_vector,
       e.embedding,
       e.embedding_model,
       e.status AS embedding_status,
       source.url AS source_url,
       source.crawled_at AS observed_at
FROM ford.documents AS d
LEFT JOIN ford.document_embeddings AS e ON e.document_id = d.id
LEFT JOIN ford.pages AS source ON source.id = d.page_id
WHERE btrim(coalesce(d.semantic_content, d.content)) <> '';
```

- [x] **Step 4: Seed ready, pending, wrong-model, missing-embedding, and missing-source rows in the `_test` integration database**

Update `catalog-read-views.test.ts` using `integrationDatabaseUrl()`. Insert a 1,024-value `bge-m3` ready row, a pending row, an incompatible-model row, a row without an embedding row, and a row without source evidence. Assert the view returns only source-backed evidence with exact model/year/trim/offer selectors and preserves each status/model needed for fail-closed handling.

- [x] **Step 5: Run Node unit and integration gates**

```bash
npm run test:unit
npm run build
npm run lint
npm run test:integration
```

Run from `/private/tmp/ford-mia-phase1-mcp`; first verify `FORD_TEST_DATABASE_URL` names a disposable `*_test` database before integration. Expected: all commands pass; no production DB is used or mutated.

### Task 4: Implement Service BGE-M3 retrieval with lexical fallback

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogPort.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogRetrievalPort.java` — public retrieval interface consumed by the AI tools module.
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogQueryVector.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogCandidateLists.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogRetrievalCandidate.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogSearchPage.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/api/CatalogQueryEmbedder.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/application/CatalogRetrievalService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/CatalogEmbeddingProperties.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/OllamaCatalogQueryEmbedder.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/JdbcCatalogAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceConfiguration.java` — build retrieval service against the dedicated datasource and optional embedder.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/CatalogTools.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java` — configure the catalog embedder from the existing Access-protected `OllamaApi` and wire the retrieval port into tools.
- Modify: `ford-ai-service/src/main/java/com/ford/service/knowledge/application/ReciprocalRankFusion.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/architecture/ModuleApiBoundaryTest.java` — require a type-level named interface for the shared RRF utility.
- Tests: `CatalogToolsTest.java`, `DecisionPolicyTest.java`, `CatalogDataSourceConfigurationTest.java`, `ReciprocalRankFusionTest.java`, `ModuleApiBoundaryTest.java`, `CatalogSemanticRetrievalTest.java`, `OllamaCatalogQueryEmbedderTest.java`, and `CatalogSemanticRetrievalIntegrationTest.java`.

**Interfaces:**
- `CatalogQueryVector(String model, List<Float> values)` validates nonblank model, exactly 1,024 finite values, and defensive copies.
- `CatalogQueryEmbedder.embed(String query)` returns `Optional<CatalogQueryVector>`; missing endpoint, Access failure, timeout, model mismatch, or wrong dimensions yields empty and a content-free `unavailable` reason.
- `CatalogRetrievalCandidate<T>(String stableId, String searchText, T evidence)` carries a stable read-view identifier for fusion and the structured evidence sent to the callback.
- `CatalogCandidateLists<T>(List<CatalogRetrievalCandidate<T>> fullText, List<CatalogRetrievalCandidate<T>> trigram, List<CatalogRetrievalCandidate<T>> vector, String semanticSearch)` carries three bounded candidate lists and one of `ready`, `unavailable`, or `not_requested`.
- `CatalogPort.searchCandidates(VehicleSearchQuery, CatalogQueryVector)` and `offerCandidates(OfferQuery, CatalogQueryVector)` return `CatalogCandidateLists<VehicleEvidence>` and `<OfferEvidence>`; default methods wrap existing lexical reads and report `unavailable`.
- `CatalogRetrievalPort.searchVehicles(VehicleSearchQuery)` and `.searchOffers(OfferQuery)` return `CatalogSearchPage<T>`; `CatalogRetrievalService` embeds only nonblank free-text queries, fuses candidate IDs across all three lists, and returns structured evidence.
- `CatalogSearchPage<T>(String semanticSearch, int resultCount, List<T> results)` reports `ready`, `unavailable`, or `not_requested` truthfully.
- `ReciprocalRankFusion.mergeById(List<List<T>>, Function<T,String>, int)` is the generic deterministic fusion method; the existing two-list `Document` overload delegates to it so knowledge RAG behavior stays unchanged.

Add these immutable API contracts (place `CatalogQueryVector`, `CatalogRetrievalCandidate`, `CatalogCandidateLists`, `CatalogSearchPage`, and `CatalogQueryEmbedder` in the exported `catalog.api` package):

```java
public record CatalogQueryVector(String storedModel, List<Float> values) {
    public CatalogQueryVector {
        if (!"bge-m3".equals(storedModel)) {
            throw new IllegalArgumentException("catalog query vector model must be bge-m3");
        }
        if (values == null || values.size() != 1024
                || values.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("catalog query vector must contain 1024 finite values");
        }
        values = List.copyOf(values);
    }
}

public record CatalogRetrievalCandidate<T>(String stableId, String searchText, T evidence) {
    public CatalogRetrievalCandidate {
        if (stableId == null || stableId.isBlank()) throw new IllegalArgumentException("stableId is required");
        searchText = searchText == null ? "" : searchText;
        java.util.Objects.requireNonNull(evidence, "structured evidence is required");
    }
}

public record CatalogCandidateLists<T>(List<CatalogRetrievalCandidate<T>> fullText,
                                        List<CatalogRetrievalCandidate<T>> trigram,
                                        List<CatalogRetrievalCandidate<T>> vector,
                                        String semanticSearch) {
    public CatalogCandidateLists {
        fullText = List.copyOf(fullText == null ? List.of() : fullText);
        trigram = List.copyOf(trigram == null ? List.of() : trigram);
        vector = List.copyOf(vector == null ? List.of() : vector);
        if (!Set.of("ready", "unavailable", "not_requested").contains(semanticSearch)) {
            throw new IllegalArgumentException("unsupported semantic search state");
        }
    }
}

public record CatalogSearchPage<T>(String semanticSearch, int resultCount, List<T> results) {
    public CatalogSearchPage {
        if (!Set.of("ready", "unavailable", "not_requested").contains(semanticSearch)) {
            throw new IllegalArgumentException("unsupported semantic search state");
        }
        if (resultCount < 0) throw new IllegalArgumentException("resultCount cannot be negative");
        results = List.copyOf(results == null ? List.of() : results);
    }
}

public interface CatalogQueryEmbedder {
    java.util.Optional<CatalogQueryVector> embed(String query);
}

public interface CatalogRetrievalPort {
    CatalogSearchPage<VehicleEvidence> searchVehicles(VehicleSearchQuery query);
    CatalogSearchPage<OfferEvidence> searchOffers(OfferQuery query);
}
```

The embedder returns empty on timeout, Access failure, or wrong response dimensions; it logs only a bounded failure category, not query text or credentials. `CatalogPort` gains the two candidate methods while its existing exact `search` and `offers` methods remain available.

- [x] **Step 1: Add failing tests for embedding model/dimension validation and semantic fallback**

Use a fake `CatalogQueryEmbedder` and `CatalogPort` to verify a wrong model tag, vector dimension other than 1,024, timeout, or empty endpoint result returns lexical candidates with `semanticSearch="unavailable"`; verify no vector SQL runs. Verify exact-filter-only queries report `not_requested`, never `ready`.

- [x] **Step 2: Add failing tests for RRF across lexical, trigram, and vector lists**

Assert a candidate present in two lists outranks a lower single-list result, duplicates collapse by stable document/offer ID, ties are deterministic, and the limit is enforced. Preserve the existing two-list `ReciprocalRankFusion` tests unchanged.

The existing API remains supported by delegation:

```java
public List<Document> merge(List<Document> vectorResults, List<Document> keywordResults, int limit) {
    return mergeById(List.of(vectorResults, keywordResults), Document::getId, limit);
}
```

- [x] **Step 3: Add failing adapter tests for view-based candidate SQL**

Use `/private/tmp/ford-uruapan-release/ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogSemanticRetrievalIntegrationTest.java`. Seed matching and nonmatching model/year and offer documents, compatible and wrong-model vectors, pending rows, and a row without observed source evidence. Assert exact vehicle model/year and offer model/year/type/date filters run before ranking, incompatible/missing-source rows are excluded from vector candidates, and each candidate maps to source-backed structured evidence.

Vector query contract:

```sql
SELECT base.model_year_id::text AS stable_id,
       base.model, base.year, base.price, base.currency,
       base.model_page_url, base.price_source_url, base.observed_at,
       1 - (d.embedding <=> cast(:queryVector as vector)) AS score
FROM ford_read.catalog_documents_v1 AS d
JOIN ford_read.vehicle_search_v1 AS base ON base.model_year_id = d.model_year_id
WHERE d.offer_id IS NULL
  AND d.source_url IS NOT NULL AND d.observed_at IS NOT NULL
  AND d.embedding_status = 'ready' AND d.embedding_model = :storedModel
  AND d.embedding IS NOT NULL
  AND (:model IS NULL OR lower(base.model) = lower(:model))
  AND (:modelYear IS NULL OR base.year = :modelYear)
ORDER BY d.embedding <=> cast(:queryVector as vector), d.document_id
LIMIT :candidateLimit;
```

The offer version joins `catalog_documents_v1.offer_id` to `offers_v2.promotion_id`. Apply every requested model/year/price/type/provider/term/validity filter before each lexical, trigram, or vector rank step. Use indexed full-text and normalized-model trigram retrieval; keep the vector `ORDER BY distance LIMIT` shape indexable, overfetch a bounded number of rows, then deduplicate by stable entity ID before RRF. Final price/promotion/fact fields still come from structured views.

- [x] **Step 4: Implement the embedder and candidate queries**

Use the dedicated `FORD_CATALOG_EMBEDDING_MODEL` property, default `bge-m3`; do not change global `FORD_AI_EMBEDDING_MODEL_VERSION` for knowledge/conversation embeddings. `OllamaCatalogQueryEmbedder` calls the existing Access-configured `OllamaApi.embed` endpoint with the explicit catalog model and accepts only a matching response tag and 1,024 finite values. Keep full-text candidates on `search_vector`, trigram candidates on the existing normalized model index and structured-filtered offer titles, and vector candidates on `embedding <=> cast(:queryVector as vector)` with `embedding_model=:storedModel`. Return semantic state `ready` only when a compatible stored vector exists and the vector query succeeds; otherwise preserve lexical/trigram results with `unavailable`.

- [x] **Step 5: Wire all free-text callback paths and expose semantic state**

Update `search_vehicles`, `search_offers`, and `search_vehicle_and_offers` to pass the explicit user query and exact filters into the retrieval service. Keep exact lookup, promotion validity, price, and comparison methods on structured views. Report `ready` only after a matching BGE-M3 vector query executed; preserve lexical results with `unavailable` on failure.

- [x] **Step 6: Verify focused Service tests and the full unit suite**

Run from `ford-ai-service`:

```bash
./gradlew test --tests 'com.ford.service.ai.CatalogToolsTest' --tests 'com.ford.service.ai.DecisionPolicyTest' --tests 'com.ford.service.ai.CatalogEmbeddingPropertiesTest' --tests 'com.ford.service.ai.OllamaCatalogQueryEmbedderTest' --tests 'com.ford.service.ai.SpringAiConfigurationSliceTest' --tests 'com.ford.service.catalog.CatalogDataSourceConfigurationTest' --tests 'com.ford.service.catalog.CatalogSemanticRetrievalTest' --tests 'com.ford.service.knowledge.application.ReciprocalRankFusionTest' --tests 'com.ford.service.architecture.ModuleApiBoundaryTest'
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
./gradlew integrationTest --tests 'com.ford.service.catalog.CatalogSemanticRetrievalIntegrationTest' --rerun-tasks --no-build-cache --max-workers=1
```

Expected: all exact-source, selector, fallback, and status assertions pass; global knowledge/conversation embeddings remain on their previous model configuration.

### Task 5: Build and run the reviewed retrieval/grounding evaluation

**Files:**
- Create: `ford-ai-service/src/integrationTest/resources/catalog/catalog-retrieval-eval.jsonl` — exactly 100 reviewed Spanish cases seeded from the 198-case Ford-scraping corpus and revalidated against the disposable snapshot.
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/catalog/CatalogRetrievalEvaluationTest.java` — calculate retrieval, fact-source, abstention, and p95 metrics against the disposable source snapshot and a local BGE-M3 endpoint.
- Modify: `ford-ai-service/docs/operations/catalog-read-only-access.md`
- Read only: `ford-ai-service/src/main/java/com/ford/service/ai/application/EvidenceGroundedResponsePolicy.java`, `CatalogFactResponseRenderer.java`, and `ford-scraping/tests` source fixture patterns.

**Interfaces:**
- Each case records query text, tool family, exact model/year/trim selectors, expected document/evidence keys, whether a result should exist, volatility class, and source/grounding expectations.
- Evaluation reports lexical baseline, current fusion, RRF, and (only if justified) existing Jev reranker; it records hit@5, recall@5, MRR, nDCG@5, fact coverage, abstention/never-promise violations, and p50/p95 latency.

- [ ] **Step 1: Add a schema/loader test for the 100-case corpus**

Require exactly 100 nonblank, unique case IDs; every positive case has at least one expected source-backed evidence key; every no-evidence case declares an expected abstention; model/year/trim fields are explicit selectors and not inferred from answer text.

Each JSON entry uses this shape, with actual source-backed records in the checked-in set:

```json
{
  "caseId": "bronco-2026-badlands-exact-facts",
  "query": "¿Qué precio y ficha técnica tiene la Bronco 2026 Badlands 2 Puertas?",
  "toolFamily": "VEHICLE_DETAILS",
  "model": "BRONCO",
  "modelYear": 2026,
  "trim": "Badlands 2 Puertas",
  "expectedDocumentKeys": ["bronco-2026-badlands-profile"],
  "expectedSourceUrls": ["https://www.forduruapan.mx/BRONCO/26/Ficha-Tecnica/"],
  "expectEvidence": true
}
```

- [ ] **Step 2: Review the existing 198-case seed corpus and create 100 source-backed retrieval cases**

Use `Ford-scraping/evals/catalog-qa/questions.jsonl` as seed material, not as ground truth: its reference answers can contain extraction artifacts and its 198 cases primarily exercise `get_vehicle`. Revalidate every expected source and fact against the current versioned read views; do not copy `referenceAnswer` as an unquestioned label. Select/normalize valid seed cases and add source-backed comparisons, offer/promotion, semantic paraphrase, model-only/no-year, out-of-catalog/no-evidence, and approved narrative cases so the final set covers all intended routes. Keep volatile catalog questions separate from approved-document RAG questions. Use stable source-backed identifiers, not list position.

- [ ] **Step 3: Run the evaluation against the lexical baseline and each candidate fusion**

Run the deterministic evaluation with the candidate read view and BGE-M3 endpoint. Record per-query evidence IDs, semantic state, metrics, and latency; do not log secret values or raw customer data. Require zero unsupported model/year/trim/price/promotion claims and zero never-promise violations. Keep the existing fusion unless RRF measurably improves the reviewed set without reducing grounding or exceeding the first-reply latency target (<1 minute).

- [ ] **Step 4: Evaluate reranking only after recall is established**

Run the existing `JevDocumentReranker` over the bounded candidate set only if no-reranker recall is adequate. Compare answer/evidence metrics and p95 latency/cost; leave it disabled unless its improvement is material and its measured runtime cost fits the existing release budget.

- [ ] **Step 5: Add grounding regressions for every structured result family**

Use source-backed catalog results to assert the response policy accepts grounded price/promotion/model facts and rejects unsupported numbers, invented URLs, mismatched selectors, unknown validity presented as active, and never-promise language.

### Task 6: Verify Ollama Access and production reader readiness

**Files:**
- Modify: `ford-ai-service/deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `ford-ai-service/.github/workflows/release.yml`
- Modify: `ford-ai-service/deployment/terraform/runtime-vault-identity.tf` and its plan guard tests only if a new named Access secret requires a new runtime read grant.
- Verify/update: Ford-scraping `main` currently pins action SHA `a878ad1ba1dd4a25ef814ea4b69ac817ae32165a`; confirm the exact commit exists upstream and contains both reader provisioning and the scheduler-container X509 POST probe before the protected release.

- [ ] **Step 1: Verify the protected Ollama endpoint and Access credential source by name/presence only**

Check the existing Cloudflare Access client ID, `FORD_AI_LOCAL_BASE_URL`, and any existing OCI Vault secret OCID mapping. Never read or print the secret value. The current Service environment has no `FORD_AI_OLLAMA_ACCESS_CLIENT_SECRET` mapping; its app template defaults to `https://ollama.example.invalid`.

- [ ] **Step 2: Stop for user provisioning if no protected service token exists**

If no existing Access service token and OCI Vault secret are available, ask the user to provision a scoped Access service token and store its secret in OCI Vault. Request only the service-token client ID, Vault secret OCID, allowed hostname, and Vault policy approval; never request the secret contents in chat. Do not disable Cloudflare Access or point Service at Ollama's loopback-only port.

- [ ] **Step 3: Run the protected Ford-scraping scheduler release first**

From merged Ford-scraping `main`, tag the next unused version after `v0.1.13` only after Node lint/unit/build and `catalog-read-views` integration tests pass. The workflow's existing `scheduler-postgres-password-secret-ocid` input must resolve through OCI Vault; release task order is verified backup, migration, reader role grant/repair, SELECT/denied-write verification, then single scheduler replacement. Preserve the current scheduler on any pre-swap failure.

- [ ] **Step 4: Verify role grants and scheduler state**

Require the protected release artifact to show reader SELECT success, INSERT/UPDATE/DELETE/TRUNCATE denial, no `ford` schema usage, no role memberships, `default_transaction_read_only=on`, and exactly one running scheduler. Follow with `Deployment Connectivity Preflight` and inspect its release IDs and scheduler identity.

- [ ] **Step 5: Add only the required Service embedding-secret mapping**

If a protected Ollama Access service token exists, add its existing client ID and Vault OCID to the Service production environment/template/action path; allow the Service instance principal only the named secret read. Keep the secret value out of GitHub variables, CI logs, and Ansible output. If Terraform policy changes are required, use the protected Terraform plan, review the exact one-secret grant, then obtain its protected apply approval.

### Task 7: Enable callbacks through a protected Service release

**Files:**
- Modify: `ford-ai-service/.github/workflows/release.yml` only for the catalog readiness smoke if needed.
- Modify: `ford-ai-service/docs/operations/catalog-read-only-access.md` with final verified runtime configuration and rollback steps.
- Modify: `/Users/miguelangeldelgadillozarate/Development/Ford-scraping/tasks/todo.md` per repository task format.

- [ ] **Step 1: Confirm the release candidate passes the complete gates**

Run the full Service unit and integration suites after Task 6; run the 100-query evaluation with the matching BGE-M3 service; verify the exact tagged Service source is reachable from Service `main`. Keep the production feature flag false during these checks.

- [ ] **Step 2: Configure production variables after reader and embedding access are proven**

Read the configured private data-host IP without printing it, construct `jdbc:postgresql://${OCI_DATA_PRIVATE_IP}:5432/ford_service?ApplicationName=ford-service-catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true`, then set `FORD_CATALOG_JDBC_URL`, `FORD_CATALOG_USERNAME=ford_catalog_reader`, the verified BGE-M3 endpoint/model/Access mapping, and `FORD_CATALOG_TOOLS_ENABLED=true` in the protected Service production environment. Never place password or Access secret contents in variables or commits.

- [ ] **Step 3: Tag and publish the next Service patch version**

Use the next unused canonical patch tag after `v0.1.26`, verify tagged CI (unit, integration, retrieval evaluation, security, image scan, attestation), then run `Release selected Ford repositories` validation for Service only.

- [ ] **Step 4: Dispatch the protected Service release and keep approval gates intact**

Run the release coordinator selecting Service only. Complete its `release-all-approval` and `production` environment approvals per authorized user request. The Service release must pass its role permission preflight, dedicated datasource initialization/readiness check, blue-green candidate health, stable health, webhook smoke, and callback-registration smoke before completion.

- [ ] **Step 5: Verify production and rollback readiness**

Run the read-only Deployment Connectivity Preflight after deployment. Require Service/Collector health UP, SELECT-only reader validation, all expected callback names logged as registered, one healthy scheduler, and a source-backed catalog smoke. Verify the release artifact’s tag/source SHA/image digest. If any check fails, set the feature flag false or restore the prior Service digest; do not stop the scheduler or Cloudflared/Ollama.

## Release blockers / explicit stop conditions

- No direct production DB edits outside the protected scheduler role-provision step.
- If the Access service token is absent, production semantic retrieval and callback enablement stay blocked until the user provides an approved token source; no secret value is pasted into chat.
- If BGE-M3 model identity/dimension differs from the stored vectors, keep semantic state unavailable and do not compare mismatched vectors.
- If the reviewed 100-query set shows unsupported facts, wrong selectors, poor evidence, or latency beyond the existing `<1 minute` initial-response objective, keep callbacks disabled while fixing the measured cause.
