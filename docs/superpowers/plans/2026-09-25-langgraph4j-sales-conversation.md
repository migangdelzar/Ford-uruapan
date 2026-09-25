# LangGraph4j Ford Sales Conversation Implementation Plan

> **For agentic workers:** Use inline execution in this session as the user requested continued work through deployment. Execute tasks in order, test-first, with review checkpoints after each vertical slice. Steps use checkbox syntax for tracking.

**Goal:** Put LangGraph4j in charge of bounded chat routing, retain Spring AI for the model and authorized MCP/RAG tools, implement durable WARM/HOT human routing, and verify the deployed service on emme-1 with its data dependencies on emme-2.

**Architecture:** A LangGraph4j `StateGraph` runs once per accepted inbound event and calls the existing Spring AI assistant through an injected application port. PostgreSQL conversation, lead-journey, handoff/follow-up, and outbox records remain canonical business state; the LangGraph saver stores only execution checkpoints. Each accepted turn commits idempotently, and graph activation remains feature-flagged for rollback.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1, Spring Modulith 2.1.1, LangGraph4j (candidate 1.9.1 pending compatibility proof), PostgreSQL, Liquibase, Gradle, JUnit 5, AssertJ, Testcontainers.

## Global Constraints

- Keep Spring Boot 4.1.0, Spring AI 2.0.1, and Java 25; do not downgrade the BOM to fit LangGraph4j.
- Use LangGraph4j custom graph/core APIs; do not add its ready-made ReAct executor as a second model/tool loop.
- Verify exact artifact/API compatibility before pinning LangGraph4j; if the Spring AI adapter conflicts, keep Spring AI behind the graph through the existing application service.
- Conversation transcript, CRM, lead journey, follow-up, handoff, and outbox remain canonical; graph checkpoints are not another transcript or sales database.
- Checkpoints must contain only allowlisted identifiers, node/route state, and version/status metadata. Do not persist raw message bodies, response drafts, secrets, PII, or raw MCP/RAG payloads in checkpoints.
- Every business read/write is scoped by tenant and conversation. Stable inbound-message idempotency prevents duplicate assistant turns, outbox rows, checkpoints with side effects, tickets, and follow-ups.
- Keep model/MCP network calls outside the short SQL transaction. No journey advancement or routing side effect may commit if the assistant turn is rejected or persistence fails.
- WARM: offer optional advisor follow-up; queue only after explicit acceptance; bot remains active. HOT and explicit human request: high-priority live queue; explicit request bypasses score. A pending ticket never means an advisor has claimed the conversation.
- Keep `FORD_HUMAN_HANDOFF_ENABLED` as kill switch. Enable it for this approved rollout only after operator list/claim is verified, through the existing `mise run deploy:secrets` / Ansible path.
- Use additive Liquibase changes only; no DB reset/drop or tenant-ID mutation.
- Do not send a live WhatsApp message to an unapproved recipient as a deployment test. Use integration fixtures/sandbox; request an authorized test recipient before any production outbound synthetic message.
- Local baseline on 2026-09-25: `env -u DOCKER_HOST ./gradlew test` succeeded (`BUILD SUCCESSFUL`, 24s). Docker-backed integration tests were not part of this baseline.
- Deployment roles: `mise run deploy:app` targets app/emme-1; `mise run deploy:data` targets data/emme-2. Do not deploy app on emme-2 under the current inventory.

## File Map

| File(s) | Responsibility |
|---|---|
| `ford-ai-service/gradle/libs.versions.toml`, `build.gradle.kts` | Pin and wire only LangGraph4j modules proven compatible with this BOM. |
| `ford-ai-service/src/main/java/com/ford/service/conversations/application/graph/ConversationGraphState.java` | Bounded typed execution state and tenant/thread/inbound references. |
| `.../graph/ConversationGraphConfiguration.java`, `ConversationGraphRunner.java` | Build/compile the graph and invoke it from the workflow boundary. |
| `.../graph/*Node.java` | Load scoped state, call assistant once, choose route, and commit accepted result; one responsibility per node. |
| `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java` | Keep Spring Modulith event boundary and delegate routing to the graph. |
| `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationTurnCommitService.java` | Commit assistant transcript, journey, route aggregate, and outbox intent idempotently in the existing transaction boundary. |
| `ford-ai-service/src/main/java/com/ford/service/ai/application/LeadQualificationPolicy.java` | Evolve the existing policy and tests to return explainable fit/intent/temperature reasons without lexical-only deterministic response routing. |
| `ford-ai-service/src/main/java/com/ford/service/handoff/**` | Add live queue priority/reason/idempotency and preserve operator list/claim/pause/resume authorization. |
| `ford-ai-service/src/main/java/com/ford/service/followups/**` (new) | Persist WARM offer/consent and lower-priority follow-up queue without changing bot-active state. |
| `ford-ai-service/src/main/resources/db/changelog/changes/013-lead-follow-up.yaml`, `014-handoff-priority.yaml` | Add tenant-scoped warm follow-up and live-handoff priority/idempotency fields without dropping existing data. |
| `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`, `.../langgraph/db.changelog.yaml` | Register additive business migrations and separately version the saver schema lifecycle. |
| `ford-ai-service/src/main/resources/application.yml`, `.../application-prod.yml`, `deployment/ansible/roles/app/templates/app.env.j2` | Add graph feature flag and safe production wiring; preserve handoff kill switch. |
| `ford-ai-service/.mise/tasks/deploy/flags`, `.mise/lib/remote.sh`, `deployment/ansible/roles/app/templates/app.env.j2` | Set only allowlisted non-secret runtime flags through mise and propagate through generated Ansible inventory. |
| `ford-ai-service/.mise/tasks/deploy/app` | Accept an explicit immutable image reference so the just-published GHCR tag is deployed without editing image config by hand. |
| `ford-ai-service/src/test/java/com/ford/service/conversations/graph/**` | Fast state, graph path, retry, and routing tests with injected fakes. |
| `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/graph/**` | PostgreSQL saver reopen, tenant scope, SQL/migration, rollback, and idempotency tests. |
| `ford-ai-service/src/test/java/com/ford/service/followups/**`, `src/integrationTest/java/com/ford/service/followups/**` | Warm offer, consent, duplicate event, queue, tenant authorization, and persistence tests. |
| `ford-ai-service/src/test/java/com/ford/service/handoff/**`, `src/integrationTest/java/com/ford/service/handoff/**` | Priority queue, explicit/HOT escalation, operator claim, no-false-transfer, and disabled-flag tests. |
| `ford-ai-service/scripts/ai/openrouter-chat-eval.test.mjs`, `fixtures/human-friendly-conversations.json` | Extend the existing model-agnostic evaluation with journey, grounded RAG, repetition, and escalation cases. |
| `ford-ai-service/docs/operations.md`, deployment docs, current spec/plan | Document feature flags, safe operator behavior, schema/backup, deploy, verify, and rollback. |

## Task 1: Prove LangGraph4j and PostgreSQL saver compatibility `[DONE]`

**Files:** modify `ford-ai-service/gradle/libs.versions.toml`, `ford-ai-service/build.gradle.kts`; create `ford-ai-service/src/test/java/com/ford/service/conversations/graph/LangGraph4jCompatibilityTest.java`.

1. Add a failing compile/runtime test using the documented LangGraph4j core `StateGraph`, `RunnableConfig`, and `CompiledGraph` API; assert a two-node graph returns the updated state.
2. Run `env -u DOCKER_HOST ./gradlew test --tests '*LangGraph4jCompatibilityTest'`; verify it fails first because the dependency/API is absent.
3. Add version-catalog candidates `org.bsc.langgraph4j:langgraph4j-core:1.9.1`, `org.bsc.langgraph4j:langgraph4j-spring-ai:1.9.1`, and `org.bsc.langgraph4j:langgraph4j-postgres-saver:1.9.1`; depend only on modules the compile probe uses. Let Spring AI continue to be managed by its 2.0.1 BOM.
4. Re-run the focused test and `env -u DOCKER_HOST ./gradlew dependencyInsight --dependency langgraph4j --configuration runtimeClasspath`; require `BUILD SUCCESSFUL`, no Spring AI version override, and a passing graph invocation. If any candidate conflicts, remove only the incompatible optional adapter and repeat using core plus the existing Spring AI port.
5. Commit `build.gradle.kts`, version catalog, and test as `build(ai): verify LangGraph4j compatibility`.

## Task 2: Define graph state, node boundaries, and finite route `[DONE]`

**Files:** create `conversations/application/graph/ConversationGraphState.java`, `ConversationGraphConfiguration.java`, `ConversationGraphRunner.java`, and focused node classes; create unit tests under `src/test/java/com/ford/service/conversations/graph/`.

1. Write tests for a normal answer, WARM, HOT/direct-human, and processor failure. Each asserts a single processor invocation maximum, no backward edge, and one terminal route. Assert the graph input/state contains only tenant/conversation/receipt UUIDs and safe outcome codes.
2. Run `env -u DOCKER_HOST ./gradlew test --tests '*ConversationGraph*Test'`; verify the new graph contract is absent and tests fail for that reason.
3. Implement typed state with only tenant/conversation/receipt UUID references plus allowlisted route/status/schema metadata. A single injected turn processor reloads canonical user text and business context, calls Spring AI once, applies policy, and commits before returning safe metadata. Conditional graph edges select terminal route nodes; no response, memory, model, or tool content enters `AgentState`.
4. Re-run focused tests; require all routes to terminate once, no provider failure to create a successful route, and no unauthorized tool access to reach the assistant callback.
5. Commit as `feat(conversations): add bounded LangGraph4j workflow`.

## Task 3: Add safe, tenant-scoped PostgreSQL checkpoints `[DONE]`

**Files:** create/update graph checkpoint configuration and serializer in `conversations/application/graph/`; add isolated saver DDL through a separate versioned schema/changelog path; create `src/integrationTest/java/com/ford/service/conversations/graph/PostgresGraphCheckpointIntegrationTest.java`.

1. Write PostgreSQL-backed tests for checkpoint save/load after process-context recreation, per-receipt tenant/conversation thread-key separation, optimistic/concurrent update behavior, and restart from a safe node boundary. Inspect serialized state and assert no user text, memory text, response draft, secret, PII, or MCP/RAG payload appears.
2. Run `env -u DOCKER_HOST ./gradlew integrationTest --tests '*PostgresGraphCheckpointIntegrationTest'`; verify it fails first on missing saver wiring/schema.
3. Configure the verified LangGraph4j PostgreSQL V2 saver using the existing application `DataSource`. Isolate saver tables/schema from business-table ownership; version and document its creation, upgrade, retention, backup, and rollback in `src/main/resources/db/changelog/langgraph/db.changelog.yaml`. Use one thread per accepted receipt to prevent cross-turn state reuse; persist only allowlisted UUID references and route/status/schema metadata. Reload turn text from the tenant-scoped canonical transcript by receipt ID inside the processor.
4. Re-run the integration test; require clean saver close/reopen and no data leakage. Confirm a missing checkpoint can be rebuilt from transcript + lead journey without losing business state.
5. Commit as `feat(conversations): persist safe graph checkpoints`.

## Task 4: Keep Spring AI and Ford tools inside one graph node

**Files:** modify `ConversationAssistantAdapter.java`, `ai/application/SpringAiAssistantService.java`, and the existing Spring AI ChatClient adapter only where needed; add graph/assistant boundary tests.

1. Add tests asserting the response node calls the existing `ConversationAssistant` exactly once, passes tenant-safe memory/journey context, preserves current authorized Ford MCP/RAG callbacks, and does not start a second ReAct executor or extraction model call.
2. Run focused adapter tests and record the failing assertion before implementation.
3. Adapt the existing assistant port as the graph’s model/tool node; keep provider selection, response-quality validation, tool authorization, and memory prompt logic in their existing owners. If typed model routing data needs a change, return it with the existing assistant response rather than making another model call.
4. Run `env -u DOCKER_HOST ./gradlew test --tests '*SpringAiChatCompletionAdapterTest' --tests '*ConversationAssistantAdapterTest' --tests '*ConversationGraph*Test'`; require pass and exactly one assistant call per graph invocation.
5. Commit as `refactor(ai): invoke Spring AI through graph node`.

## Task 5: Delegate inbound processing to the graph with idempotent commit

**Files:** modify `ConversationWorkflow.java`, `ConversationConfiguration.java`, `ConversationRepository.java`, `JdbcConversationRepository.java`; create `ConversationTurnCommitService.java` and unit/PostgreSQL integration tests.

1. Write tests proving the inbound `InboundMessageAccepted` event delegates to the graph, duplicate provider message IDs produce one assistant transcript/outbox row, a model/tool error does not advance the journey, and journey/route write failure rolls back the accepted assistant/outbox transaction.
2. Run `env -u DOCKER_HOST ./gradlew test --tests '*ConversationWorkflowTest' --tests '*ConversationTurnCommitServiceTest'`; observe expected failures before code changes.
3. Keep the Spring Modulith event listener as the input boundary; replace direct assistant/handoff branching with `ConversationGraphRunner`. Derive idempotency using tenant ID + conversation ID + provider message ID. Keep model/MCP calls outside the transaction; atomically commit assistant reply, accepted lead journey update, route aggregate, and outbox intent in one transaction.
4. Run focused unit tests and `env -u DOCKER_HOST ./gradlew integrationTest --tests '*LeadJourneyTurnIntegrationTest' --tests '*ConversationRepositoryIntegrationTest'`; require duplicate delivery and injected rollback cases to pass.
5. Commit as `refactor(conversations): route inbound turns through graph`.

## Task 6: Implement explainable fit/intent and WARM follow-up lifecycle

**Files:** modify `ai/application/LeadQualificationPolicy.java` and its test; create follow-up domain/application/adapter files under `followups/`; add `013-lead-follow-up.yaml` and register it in `db.changelog-master.yaml`; add unit, API, and PostgreSQL integration tests.

1. Write tests for separate fit and buying-intent inputs; medium non-urgent interest offers follow-up once; explicit acceptance queues exactly one item; decline creates none and suppresses consecutive offers; ambiguous acceptance asks a clarification; bot remains `BOT_ACTIVE` while the item waits; only explicit statements count as confirmed user facts.
2. Run `env -u DOCKER_HOST ./gradlew test --tests '*LeadQualificationPolicyTest' --tests '*LeadFollowUp*Test'`; establish red results before implementation.
3. Add explainable reason/evidence codes without claiming calibrated probabilities. Add tenant-scoped follow-up statuses `OFFERED`, `DECLINED`, `QUEUED`, `CLAIMED`, and `CLOSED`; enforce a unique idempotency key by tenant/conversation/inbound provider ID. Persist offer/consent and queue status; do not change conversation state on WARM acceptance.
4. Re-run unit tests and `env -u DOCKER_HOST ./gradlew integrationTest --tests '*LeadFollowUpRepositoryIntegrationTest'`; require PostgreSQL uniqueness, transaction, tenant-isolation, and consent behavior to pass.
5. Commit as `feat(leads): queue consented warm follow-ups`.

## Task 7: Implement HOT/direct live queue and truthful operator lifecycle

**Files:** modify `handoff/domain/HandoffTicket.java`, `HandoffStatus.java`, `DefaultHandoffService.java`, `HandoffRepository` adapters, `OperatorConversationHandoffController.java`, `HandoffNotificationListener.java`; add additive migration and tests.

1. Write tests for HOT creating one high-priority ticket, explicit human request bypassing fit/score, operator tenant isolation and versioned claim, claimed ticket pausing automated replies, idempotent retries, disabled flag producing no ticket, and pending ticket copy not claiming an advisor has joined.
2. Run `env -u DOCKER_HOST ./gradlew test --tests '*Handoff*Test' --tests '*HumanHandoffPolicyTest'`; confirm failing behavior before implementation.
3. Extend the existing durable queue additively with priority/reason/idempotency. Keep list/claim/pause/resume authorization and conversation lifecycle. Replace the misleading audit-only “notification requested” behavior with an accurately named durable operator-queue event/audit; use the existing operator-facing queue, and do not invent a new notification vendor. Make queue age and claimed status observable.
4. Re-run unit tests and `env -u DOCKER_HOST ./gradlew integrationTest --tests '*HandoffApiIntegrationTest' --tests '*HumanTakeoverFlowTest' --tests '*FinanceLeadHandoffFlowTest'`; require pending vs active semantics and retries to pass.
5. Commit as `feat(handoff): route hot leads to priority queue`.

## Task 8: Add graph kill switch, prompt, and model-agnostic behavior evaluation

**Files:** modify `ConversationProperties.java`, `application.yml`, `application-prod.yml`, `deployment/ansible/roles/app/templates/app.env.j2`, `src/main/resources/prompts/ford-uruapan-system.md`, `scripts/ai/openrouter-chat-eval.test.mjs`, and `scripts/ai/fixtures/human-friendly-conversations.json`; create `.mise/tasks/deploy/flags` and `ford-ai-service/.mise/tests/deploy-flags-test.sh`; modify `.mise/tasks/deploy/app` to accept an explicit immutable image.

1. Add evaluator fixtures for Spanish direct promotion lookup with tool evidence, missing RAG evidence, remembered vehicle follow-up, topic change, correction, no repeated qualification, warm offer/decline/acceptance, hot intent, explicit human request, and disabled handoff.
2. Run `node --test scripts/ai/openrouter-chat-eval.test.mjs`; capture failing current behavior.
3. Add a default-off `FORD_CONVERSATION_GRAPH_ENABLED` compatibility switch, with graph disabled path retaining the current workflow for rollback. Preserve the independent human-handoff switch. Rewrite prompt only to reinforce current-question priority, natural tone, verified source use, no repetition, no stage recital, consent, and truthful handoff status.
4. Add `deploy:flags` with an allowlist containing only `FORD_CONVERSATION_GRAPH_ENABLED` and `FORD_HUMAN_HANDOFF_ENABLED`; accept explicit boolean values, write the ignored `deployment/config/oci.env` with mode 0600, and never echo config contents. Make `deploy:app` accept one validated optional image argument while preserving existing Ansible option forwarding.
5. Run `bash ford-ai-service/.mise/tests/deploy-flags-test.sh`, `bash -n .mise/tasks/deploy/flags .mise/tasks/deploy/app`, `node --test scripts/ai/openrouter-chat-eval.test.mjs`, and unit tests for configuration defaults and graph-disabled compatibility; require all to pass.
6. Commit as `test(ai): evaluate graph sales and handoff behavior`.

## Task 9: Verify full application and database behavior before rollout

**Files:** update integration/evaluation tests, `ford-ai-service/docs/operations.md`, and the design/plan status.

1. Add end-to-end integration tests for `inbound → scoped memory/journey → authorized RAG/MCP → assistant reply → idempotent outbox`, WARM acceptance with bot active, HOT/direct handoff and operator claim, and restart from graph checkpoint.
2. Run `env -u DOCKER_HOST ./gradlew test` and `env -u DOCKER_HOST ./gradlew integrationTest`; use only the configured default Docker endpoint, do not switch Colima profiles. Expected: all tests pass and Liquibase starts against PostgreSQL.
3. Run `env -u DOCKER_HOST ./gradlew check`, `node --test scripts/ai/openrouter-chat-eval.test.mjs`, `git diff --check`, and `mise run check`; fix failures before deployment.
4. Update operations docs with graph/handoff flags, checkpoint/business-state ownership, data backup/restore, queue lifecycle, deployment checks, and rollback steps. Ensure app flags use the existing managed secrets/deploy path.
5. Commit as `test(ai): verify graph conversation end to end` and review all changes against the design acceptance list.

## Task 10: Deploy and verify emme-1 / emme-2 safely

**Files:** no production data file edits. Local ignored deployment config may be updated only via existing `mise run deploy:secrets` task; deployment state/archives remain ignored.

1. Inspect current `mise run deploy:status`, `mise run deploy:logs -- app`, and `mise run deploy:logs -- data` before deployment. Record health/image/outbox/handoff state without printing secret values.
2. Verify deployment configuration with `mise run deploy:check` and `mise run deploy:config`; confirm inventory still maps emme-1=app and emme-2=data. If either host is unhealthy, stop and diagnose before deploying.
3. Run `mise run backup:run`, then `mise run backup:verify`, and save the current immutable image with `mise run deploy:image-backup -- "$previous_image_ref"`. Do not run `db:reset`, `db:reset-volume`, or infrastructure destroy tasks.
4. Set `FORD_CONVERSATION_GRAPH_ENABLED=true` and `FORD_HUMAN_HANDOFF_ENABLED=true` through the allowlisted `mise run deploy:flags -- --conversation-graph true --human-handoff true` task after operator list/claim is verified. Keep secrets exclusively in the existing `mise run deploy:secrets` flow; never print, stage, or copy them elsewhere.
5. Set `commit="$(git rev-parse HEAD)"`, `tag="sha-$commit"`, and `repository="${GHCR_IMAGE_REPOSITORY:-ghcr.io/migangdelzar/ford-uruapan}"`. Publish with `mise run release:publish "$tag"`; verify that immutable tag exists in GHCR. Deploy it with `mise run deploy:app -- "$repository:$tag"`; `deploy:app` pulls the exact reference through Ansible on emme-1. Do not use a floating tag.
6. Run `mise run deploy:status`, `mise run deploy:logs -- app`, `mise run deploy:logs -- data`, and health checks afterward.
7. Verify migration success, app readiness on emme-1, PostgreSQL/Redis connectivity on emme-2, configured OpenRouter provider, MCP/RAG tool availability and a grounded source lookup using a test/sandbox conversation, graph restart continuation, WARM offer state, HOT/direct queue creation and operator claim, duplicate inbound idempotency, and outbound outbox state without sending to an unapproved recipient.
8. If the new app fails health or migrations, use the recorded previous immutable GHCR image reference with `mise run deploy:app -- "$previous_image_ref"`; keep the `deploy:image-backup` archive as an additional recovery artifact. Keep DB migrations additive/backward-compatible. Inspect logs again and report the exact blocker; do not mark deployment successful on health alone.
9. Commit any deployment-doc/automation changes separately. Do not merge to main unless the user separately requests it after emme validation.

## Dependencies and Ordering

```text
Task 1 compatibility proof
   ↓
Task 2 graph contract ──→ Task 3 checkpoint saver
   ↓                         ↓
Task 4 Spring AI node ───────┘
   ↓
Task 5 inbound graph + idempotent commit
   ├──→ Task 6 WARM qualification/follow-up
   └──→ Task 7 HOT/direct handoff
             ↓
Task 8 flags/prompt/evaluations
   ↓
Task 9 full verification/docs
   ↓
Task 10 backup + emme rollout and live verification
```

## Definition of Done

- [ ] LangGraph4j dependency and PostgreSQL saver pass a real compile + integration compatibility test without changing the Spring AI BOM.
- [ ] Graph owns inbound routing and contains no unbounded loop or duplicate Spring AI/ReAct tool executor.
- [ ] Existing lead journey remains canonical and resumes after restart; checkpoint contains only safe execution references/state.
- [ ] WARM follow-up requires explicit acceptance and leaves AI active; HOT/direct routes to an idempotent priority queue; operator claim is tenant-safe and observable.
- [ ] Current Ford facts are grounded in authorized tool results; no stale/missing source is invented.
- [ ] Unit, PostgreSQL integration, end-to-end, and evaluator tests pass with no known critical failure.
- [ ] Existing secret propagation, Ansible deployment, backup, and rollback tasks are used; no destructive production operation occurs.
- [ ] Deployed app is healthy on emme-1 and verified against PostgreSQL/Redis on emme-2; required synthetic user flows pass without unauthorized outbound messages.
- [ ] Previous deployment image remains available for rollback; logs and operator queue are checked before declaring success.
