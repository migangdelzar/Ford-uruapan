# Meta Messaging Channel Completion Implementation Plan

> **For agentic workers:** Execute inline in the isolated worktree, one task at a time. Use TDD for each behavior.

**Goal:** Restore a buildable baseline and complete safe inbound/outbound text messaging across WhatsApp, Messenger, and Instagram using the existing shared conversation workflow.

**Architecture:** Keep the current ports-and-adapters boundaries. Use channel-specific adapters as strategies for webhook normalization and outbound payloads, with the existing binding service, conversation graph, persistence, and outbox shared across channels.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring AI 2.0, Spring Modulith, Jackson, Spring `RestClient`, Gradle, JUnit 5, Mockito, PostgreSQL integration tests.

## Global Constraints

- Preserve tenant + channel + provider-account scoping and provider-message deduplication.
- Verify Meta HMAC signatures against the raw body before parsing or accepting any channel event.
- Never log customer text, media URLs, access tokens, tool arguments, or tool results.
- Keep provider secrets out of source control and sample files; preserve existing WhatsApp variable names.
- Unsupported provider callbacks are acknowledged and ignored; unsupported customer media gets the existing safe clarification.
- Keep the existing 24-hour customer-service window and do not create a new out-of-window messaging path.
- Do not reset production data, push commits, or deploy as part of this implementation.

---

## Task 1: Restore the Missing Spring AI Tool Decorator

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/ObservedToolCallback.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/ObservedToolCallbackTest.java`

**Interfaces:**
- Consumes Spring AI `ToolCallback`; exposes the same `ToolCallback` definition, metadata, and call behavior.
- Logs only tool name, safe context IDs, outcome, and elapsed milliseconds.

- [ ] Add a test that callback calls produce start/completion observations without argument/result content.
- [ ] Run `./gradlew test --tests com.ford.service.ai.ObservedToolCallbackTest`; first confirm the missing type prevents compilation.
- [ ] Implement the decorator with constructor injection of the delegate and a logger; delegate every overload unchanged.
- [ ] Re-run the focused test and `./gradlew compileJava`.
- [ ] Commit as `fix(ai): restore observed tool callback decorator`.

## Task 2: Define Per-Account Webhook Parsing

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/application/port/in/ChannelAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/application/InboundWebhookService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/application/ChannelMessageService.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/AbstractMetaChannelAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/WhatsAppChannelAdapter.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/InboundWebhookServiceTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/WhatsAppAdapterContractTest.java`

**Interfaces:**
- `ChannelAdapter.providerAccountIds(String body): List<String>` returns distinct account IDs with inbound customer messages only.
- `ChannelAdapter.parseInboundMessages(RawWebhook payload, String providerAccountId): List<InboundMessage>` parses only that account's message entries.
- `ChannelMessageService.acceptAll(List<InboundMessage>): List<InboundMessage>` persists and publishes each deduplicated message in the caller's transaction.

- [ ] Add failing tests for a WhatsApp batch with several messages, status-only entries, and multiple phone-number IDs.
- [ ] Add a failing service test proving each account resolves its own tenant binding and the adapter is invoked with that account ID.
- [ ] Implement per-account IDs and parsing; do not short-circuit a mixed status/message callback based on its first entry.
- [ ] Verify WhatsApp's existing batch, binding, and duplicate semantics remain intact with focused tests.
- [ ] Commit as `fix(channels): process Meta webhook messages per account`.

## Task 3: Complete Messenger and Instagram Inbound Contracts

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MessengerChannelAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/InstagramChannelAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/AbstractMetaChannelAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaWebhookController.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MessengerAdapterContractTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/InstagramAdapterContractTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaWebhookControllerTest.java`

- [ ] Add failing contracts for multiple entries/messages, user text, quick replies, echoes, delivery/read events, and callbacks with no customer message.
- [ ] Normalize account ID, participant ID, message ID, event time, text, and interactive label without leaking opaque payloads to the assistant.
- [ ] Return no inbound turns for echoes and non-message callbacks so the webhook can return 202 without triggering model calls.
- [ ] Preserve HMAC validation and route all three channels through the same inbound service and conversation workflow.
- [ ] Commit as `feat(channels): normalize Messenger and Instagram webhook events`.

## Task 4: Make Binding Initialization Channel-Aware

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaWebhookProperties.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/in/meta/MetaBindingStartupInitializer.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `ford-ai-service/.env.example`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaBindingStartupInitializerTest.java`

- [ ] Add failing tests for optional Messenger/Instagram bindings and for disabled/unconfigured channels doing no SQL.
- [ ] Retain legacy WhatsApp settings; add separately named, opt-in provider-account settings for Messenger and Instagram, sharing only the configured tenant ID.
- [ ] Add environment pass-through names without values and verify generated environment variable names against `application.yml`.
- [ ] Commit as `feat(channels): support opt-in Meta bindings per channel`.

## Task 5: Separate Channel-Specific Outbound Payload Strategies and Enforce the Customer Window

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/meta/MetaRestClientDeliveryAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/meta/MetaDeliveryConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/adapter/out/meta/MetaOutboxDeliveryRouter.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaRestClientDeliveryAdapterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaDeliveryConfigurationTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaOutboxDeliveryRouterTest.java`

- [ ] Add failing HTTP tests asserting WhatsApp, Messenger, and Instagram each use the correct configured endpoint, bearer token, request schema, and provider message ID parsing.
- [ ] Add channel-specific payload selection while keeping the same `ChannelDeliveryPort` and outbox contract.
- [ ] Add a router test proving a closed or missing customer window prevents a provider call and produces a permanent rejection; an open window continues to the selected channel delivery port.
- [ ] Assert provider 4xx permanent failures and transient throttling/server failures retain their existing retry classification; never assert/log message content in telemetry.
- [ ] Commit as `feat(channels): send text replies through channel-specific Meta payloads`.

## Task 6: Document Setup and Run Full Verification

**Files:**
- Modify: `ford-ai-service/docs/operations.md`
- Modify only if needed: channel adapter tests and configuration tests from prior tasks.

- [ ] Document webhook route, per-channel account IDs, endpoint/token variable names, required platform permissions, and provider-console prerequisites without including secret values.
- [ ] Run `./gradlew test integrationTest check --rerun-tasks` from `ford-ai-service`.
- [ ] Run `node --test scripts/ai/openrouter-chat-eval.test.mjs` from the repository root.
- [ ] Run `git diff --check`, confirm no unresolved paths, and inspect the final diff/status.
- [ ] Commit as `docs(channels): document Meta channel setup and verification`.

## Self-Review

- Every design requirement maps to Tasks 1–6.
- No schema change is planned: existing channel enum, binding table, message kinds, deduplication, and outbox are reused.
- Live Meta delivery cannot be proven without configured channel accounts, approved permissions, and credentials; test-double/HTTP contract tests are the local verification boundary.
