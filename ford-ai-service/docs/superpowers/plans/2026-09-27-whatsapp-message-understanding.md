# WhatsApp Message Understanding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept WhatsApp text and structured/media messages, understand supported audio/images using only already approved capable providers, classify customer intent, and route safely through the existing conversation workflow.

**Architecture:** Keep Meta payload parsing deterministic and separate from semantic classification. Persist each provider message idempotently, resolve media through narrow ports with strict limits, normalize supported content into text/typed facts, then reuse the current classifier and LangGraph4j/Spring AI flow. Unsupported content or failed interpretation receives a safe clarification.

**Tech Stack:** Java, Spring Boot 4.1.0, Spring AI 2.0.1, Spring Modulith, LangGraph4j, Liquibase, PostgreSQL, Gradle, JUnit 5, AssertJ, Mockito, Spring AI `TranscriptionModel` and message `Media` APIs where supported by resolved dependencies.

## Global Constraints

- Never add a provider or broaden the customer-data allow-list; media bytes go only to an already approved provider explicitly configured as capable for the modality.
- Never log raw webhook bodies, customer message text, transcripts, prompts, attachment/media URLs, bytes, access tokens, or secrets.
- Keep provider message ID as the deduplication key; a duplicate must not create another turn or outbound reply.
- Preserve tenant isolation and the existing sensitive-data redaction/block policy before external model calls.
- Never invent current offers or tool results; only call registered, authorized tools from the selected tool family.
- Do not persist downloaded media bytes; bound size/time and remove temporary media after interpretation.
- No production DB reset, provider change, or human-handoff policy change is in scope.
- Follow TDD: write and run a focused failing test before each behavior implementation.

---

## File map

| Path | Responsibility |
|---|---|
| `src/main/java/com/ford/service/channels/domain/InboundMessage.java` | Add typed transport kind and typed attachment references without storing media bytes. |
| `src/main/java/com/ford/service/channels/domain/InboundMessageKind.java` | Enumerate transport shape independently of semantic intent. |
| `src/main/java/com/ford/service/channels/domain/InboundAttachment.java` | Store opaque provider media ID, declared MIME type, and bounded metadata. |
| `src/main/java/com/ford/service/channels/application/port/in/ChannelAdapter.java` | Add a batch-normalization default method while retaining the existing one-message API for other Meta adapters. |
| `src/main/java/com/ford/service/channels/adapter/in/meta/AbstractMetaChannelAdapter.java` | Preserve current text normalization and provide shared safe parsing helpers. |
| `src/main/java/com/ford/service/channels/adapter/in/meta/WhatsAppChannelAdapter.java` | Normalize all WhatsApp messages in the webhook into typed inbound records. |
| `src/main/java/com/ford/service/channels/application/InboundWebhookService.java` | Send non-status webhook messages to batch acceptance. |
| `src/main/java/com/ford/service/channels/application/ChannelMessageService.java` | Persist/publish each message independently under provider-message idempotency. |
| `src/main/resources/db/changelog/changes/018-whatsapp-inbound-content.yaml` | Add backward-compatible transport kind and JSON metadata columns to `channel_message`. |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | Register the additive Liquibase change. |
| `src/main/java/com/ford/service/channels/adapter/out/JdbcInboundMessagePersistenceAdapter.java` | Persist typed kind and safe attachment metadata. |
| `src/main/java/com/ford/service/channels/api/AcceptedInboundMessage.java` | Expose kind/metadata to the asynchronous conversation worker. |
| `src/main/java/com/ford/service/conversations/adapter/out/JdbcAcceptedInboundTurnRepository.java` | Read typed inbound metadata. |
| `src/main/java/com/ford/service/channels/application/port/out/MetaMediaRetrievalPort.java` | Retrieve bounded media bytes using opaque provider media IDs; no URL/token escapes this boundary. |
| `src/main/java/com/ford/service/channels/adapter/out/meta/MetaMediaRestClientAdapter.java` | Implement Meta media metadata/download calls using injected Spring `RestClient`, configured auth, and response-size/time limits. |
| `src/main/java/com/ford/service/ai/application/ProviderCapabilities.java` | Add explicit image/audio input capability while preserving the existing three-argument constructor with both new capabilities false. |
| `src/main/java/com/ford/service/ai/application/ModelRequirements.java` | Express required image/audio capability for safe provider selection. |
| `src/main/java/com/ford/service/ai/application/DefaultModelProviderPolicy.java` | Filter candidates by requested modality and existing sensitive-data restrictions. |
| `src/main/java/com/ford/service/ai/application/InboundMediaInterpretationPort.java` | Interpret downloaded image/audio content into bounded text using an explicitly selected approved provider. |
| `src/main/java/com/ford/service/ai/application/InboundContentResolutionService.java` | Resolve plain text, captions, interactive/location data, audio transcript, image description, or safe unsupported-media response. |
| `src/main/java/com/ford/service/ai/application/InboundContentStatus.java` | Distinguish ready content from a safe clarification-required result. |
| `src/main/java/com/ford/service/channels/domain/RetrievedMedia.java` | Hold verified, size-bounded temporary media and guarantee cleanup via `AutoCloseable`. |
| `src/main/java/com/ford/service/ai/configuration/AiProperties.java` | Bind opt-in media processing, explicit approved/capable provider ID lists, byte/time limits, and no credentials. Defaults keep media processing disabled and provider lists empty. |
| `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiInboundMediaInterpretationAdapter.java` | Use Spring AI 2.0.1-supported audio/image model APIs; no direct provider SDK in application code. |
| `src/main/java/com/ford/service/channels/api/AcceptedInboundMessageRepository.java` | Read accepted inbound data and persist interpreted text to the canonical transcript under tenant/conversation/receipt scope. |
| `src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java` | Resolve typed inbound content before persisting transcript text, recording it into conversation memory, and invoking the assistant. |
| `src/main/java/com/ford/service/ai/application/ConversationIntent.java` | Add missing semantic categories without conflating intent with lead qualification. |
| `src/main/java/com/ford/service/ai/application/ToolFamily.java` | Add a family only when a registered authorized operation exists; otherwise use `NONE`. |
| `src/main/java/com/ford/service/ai/adapter/out/typesafe/TypeSafeConversationDecisionAdapter.java` | Ask the existing classifier for the expanded semantic category set and preserve evidence/confidence validation. |
| `src/main/java/com/ford/service/ai/adapter/out/rules/RulesConversationDecisionAdapter.java` | Supply deterministic fallback for explicit, high-signal category phrases and safe general fallback. |
| `src/main/java/com/ford/service/ai/application/DecisionPolicy.java` | Ensure selected category/family can access only matching registered tools; remove unsafe unrelated-tool widening for unsupported categories if tests confirm current behavior. |
| `src/test/java/com/ford/service/channels/WhatsAppAdapterContractTest.java` | WhatsApp payload parsing contract tests. |
| `src/test/java/com/ford/service/channels/ChannelMessageServiceTest.java` | Batch persistence, deduplication, and event publication tests. |
| `src/test/java/com/ford/service/channels/JdbcInboundMessagePersistenceAdapterTest.java` | Persistence mapping tests using established repository DB test fixtures. |
| `src/test/java/com/ford/service/ai/InboundContentResolutionServiceTest.java` | Modality gates, fallback, cleanup, and no-provider-leak tests. |
| `src/test/java/com/ford/service/ai/ModelProviderPolicyTest.java` | Provider capability/approval and sensitivity tests. |
| `src/test/java/com/ford/service/ai/RulesConversationDecisionAdapterTest.java` | Rule fallback coverage for the taxonomy. |
| `src/test/java/com/ford/service/ai/TypeSafeConversationDecisionAdapterTest.java` | Structured classifier mapping and confidence/fallback coverage. |
| `src/test/java/com/ford/service/ai/DecisionPolicyTest.java` | Intent/tool-family authorization matrix. |
| `src/test/java/com/ford/service/conversations/DefaultConversationTurnProcessorTest.java` | Verify content resolution precedes memory and assistant invocation. |
| `src/integrationTest/java/com/ford/service/channels/MetaInboundWorkflowIntegrationTest.java` | End-to-end webhook batch-to-receipt/graph behavior. |
| `src/integrationTest/java/com/ford/service/e2e/MetaToOutboxVerticalFlowTest.java` | Exactly-once outbound result and unsupported-media response. |
| `docs/operations.md` | Document media limits, provider capability configuration, and safe fallback diagnostics. |

## Task 1: Add semantic request categories to the existing decision pipeline

**Files:**
- Modify `src/main/java/com/ford/service/ai/application/ConversationIntent.java`
- Modify `src/main/java/com/ford/service/ai/application/ToolFamily.java` only for an actually supported registered family
- Modify `src/main/java/com/ford/service/ai/adapter/out/typesafe/TypeSafeConversationDecisionAdapter.java`
- Modify `src/main/java/com/ford/service/ai/adapter/out/rules/RulesConversationDecisionAdapter.java`
- Modify `src/main/java/com/ford/service/ai/application/DecisionPolicy.java` only where the authorization matrix requires it
- Test `src/test/java/com/ford/service/ai/RulesConversationDecisionAdapterTest.java`
- Test `src/test/java/com/ford/service/ai/TypeSafeConversationDecisionAdapterTest.java`
- Test `src/test/java/com/ford/service/ai/DecisionPolicyTest.java`

**Interfaces:** Existing `ConversationDecisionPort.decide(ClassificationContext)` and `ConversationDecision` remain the single classification contract. New values represent customer request intent only; lead stage, lead temperature, risk, handoff, and next action remain separate axes.

- [ ] **Step 1: Add failing rule tests** for greetings/general; vehicle discovery/details; offers; finance; trade-in; appointment; dealership logistics; service/parts; complaint/status; explicit human request; unsupported/out-of-scope. Verify an unknown request remains `GENERAL` with `ToolFamily.NONE` and no unrelated tool authorization.
- [ ] **Step 2: Run focused tests and confirm failure.** Run `./gradlew test --tests 'com.ford.service.ai.RulesConversationDecisionAdapterTest' --tests 'com.ford.service.ai.DecisionPolicyTest'`. Expected: new category assertions fail because current rules lack those distinctions and `ToolFamily.NONE` currently permits unrelated available tools.
- [ ] **Step 3: Add only the required intent labels and fallback rules.** Map existing tool-backed intents to existing families. Keep unsupported categories at `NONE`; do not create tool integrations or new claims. Add TypeSafe questions/labels for each category and keep enum validation/confidence fallback intact.
- [ ] **Step 4: Add classifier mapping tests** for every new label, unknown label rejection, below-threshold fallback, and risk/lead fields remaining independent. Run the three focused test classes; expected: PASS.
- [ ] **Step 5: Add exact authorization matrix tests.** Assert offers expose only registered offer tools, finance only finance tools, no-tool categories expose no unrelated tools, and sensitive requests retain existing restricted-provider behavior. Update `DecisionPolicy` minimally and run `./gradlew test --tests 'com.ford.service.ai.DecisionPolicyTest'`; expected: PASS.
- [ ] **Step 6: Commit** as `feat(ai): expand conversation request classification` with only Task 1 files.

## Task 2: Normalize WhatsApp messages into typed single/batch records

**Files:**
- Create `src/main/java/com/ford/service/channels/domain/InboundMessageKind.java`
- Create `src/main/java/com/ford/service/channels/domain/InboundAttachment.java`
- Modify `src/main/java/com/ford/service/channels/domain/InboundMessage.java`
- Modify `src/main/java/com/ford/service/channels/application/port/in/ChannelAdapter.java`
- Modify `src/main/java/com/ford/service/channels/adapter/in/meta/AbstractMetaChannelAdapter.java`
- Modify `src/main/java/com/ford/service/channels/adapter/in/meta/WhatsAppChannelAdapter.java`
- Test `src/test/java/com/ford/service/channels/WhatsAppAdapterContractTest.java`
- Run existing `src/test/java/com/ford/service/channels/MessengerAdapterContractTest.java`, `InstagramAdapterContractTest.java`, and `MetaChannelIdentityTest.java`

**Interfaces:** Add `default List<InboundMessage> parseInboundMessages(RawWebhook payload)` to `ChannelAdapter`, returning `List.of(parseInbound(payload))` to preserve other adapters. WhatsApp overrides it and parses every `/entry/*/changes/*/value/messages/*` item. Keep `parseInbound` source-compatible for current one-message callers by returning the only parsed message or rejecting a multi-message envelope with a clear `IllegalArgumentException`; webhook ingestion uses the list method.

`InboundMessageKind` values: `TEXT`, `IMAGE`, `AUDIO`, `VIDEO`, `DOCUMENT`, `LOCATION`, `INTERACTIVE`, `STICKER`, `UNSUPPORTED`. `InboundAttachment` contains only `providerMediaId`, `declaredMimeType`, `filename`, and `sizeBytes` when supplied; it never contains downloaded bytes or a URL. Add typed kind/attachment fields to `InboundMessage`, with a compatibility constructor defaulting to `TEXT` and an empty attachment list. Reject a single webhook envelope that mixes provider phone-number IDs rather than accidentally binding one tenant's messages to another account.

- [ ] **Step 1: Write failing adapter tests** for each listed kind, image caption, audio media ID/MIME, location coordinates, interactive title/id, multiple messages in one webhook, missing provider ID/sender, empty text, malformed JSON, and status callback separation.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.ford.service.channels.WhatsAppAdapterContractTest'`. Expected: parsing currently rejects non-text and loses all but a single text path.
- [ ] **Step 3: Implement typed parsing** by checking the WhatsApp message `type` field and reading only the matching documented payload fields. For unknown types, produce `UNSUPPORTED` when identity is valid; reject malformed identities. Parse all message entries while preserving their provider IDs and receive time.
- [ ] **Step 4: Run WhatsApp and other Meta adapter contract tests.** Expected: WhatsApp tests pass; Messenger/Instagram and stable single-message `parseInbound` callers remain unchanged.
- [ ] **Step 5: Commit** as `feat(channels): normalize WhatsApp message types` with only Task 2 files.

## Task 3: Accept each webhook message idempotently and persist safe metadata

**Files:**
- Modify `src/main/java/com/ford/service/channels/application/InboundWebhookService.java`
- Modify `src/main/java/com/ford/service/channels/application/ChannelMessageService.java`
- Create `src/main/resources/db/changelog/changes/018-whatsapp-inbound-content.yaml`
- Modify `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify `src/main/java/com/ford/service/channels/adapter/out/JdbcInboundMessagePersistenceAdapter.java`
- Modify `src/main/java/com/ford/service/channels/api/AcceptedInboundMessage.java`
- Modify `src/main/java/com/ford/service/channels/api/AcceptedInboundMessageRepository.java`
- Modify `src/main/java/com/ford/service/conversations/adapter/out/JdbcAcceptedInboundTurnRepository.java`
- Test `src/test/java/com/ford/service/channels/ChannelMessageServiceTest.java`
- Test `src/test/java/com/ford/service/channels/JdbcInboundMessagePersistenceAdapterTest.java`
- Extend `src/test/java/com/ford/service/conversations/JdbcAcceptedInboundTurnRepositoryTest.java`

**Interfaces:** Add `ChannelMessageService.acceptAll(RawWebhook, ChannelAdapter): List<InboundMessage>`. Keep `accept(...)` for legacy single-message callers; it rejects an envelope with more than one parsed message rather than silently dropping entries. For each parsed message, `acceptAll` creates its own receipt, applies the existing tenant/channel/provider-message deduplication key, persists, then publishes its identifier-only event. `AcceptedInboundMessage` exposes `InboundMessageKind kind()` and immutable attachment metadata alongside existing fields. Add `void persistResolvedContent(UUID tenantId, UUID conversationId, UUID receiptId, String text)` to the repository port/JDBC adapter; it updates matching `channel_message.content` and `conversation_message.content` under all three IDs before memory write, storing interpreted text but never media bytes.

- [ ] **Step 1: Write failing tests** proving two message entries produce two distinct receipts/events, duplicate of one ID does not republish, ordering is stable, status callbacks create none, and Instagram/Messenger continue using the default singleton behavior.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.ford.service.channels.ChannelMessageServiceTest'`. Expected: `acceptAll` and batch behavior are not yet present.
- [ ] **Step 3: Implement batch acceptance** so each message is independently deduplicated, persisted, and published within the existing transaction boundary; make legacy `accept(...)` reject multi-message envelopes; update `InboundWebhookService` to call `acceptAll` after signature/binding/status checks.
- [ ] **Step 4: Write migration and persistence tests** for defaulting old rows to `TEXT`, JSON metadata round-trip, and no media URL/token/bytes in persisted fields. Run the focused adapter tests using existing Testcontainers/database fixtures.
- [ ] **Step 5: Add Liquibase migration 018** for non-null `message_kind` with default `TEXT` and JSONB attachment metadata defaulting to an empty array; update JDBC insert/select mapping. Do not rewrite or reset existing rows.
- [ ] **Step 6: Run** `./gradlew test --tests 'com.ford.service.channels.*' --tests 'com.ford.service.conversations.JdbcAcceptedInboundTurnRepositoryTest'`. Expected: pass with legacy rows reading as `TEXT` and existing idempotency unchanged.
- [ ] **Step 7: Commit** as `feat(channels): persist typed inbound message metadata` with only Task 3 files.

## Task 4: Add provider capability gates and bounded media interpretation

**Files:**
- Modify `src/main/java/com/ford/service/ai/application/ProviderCapabilities.java`
- Modify `src/main/java/com/ford/service/ai/application/ModelRequirements.java`
- Modify `src/main/java/com/ford/service/ai/application/DefaultModelProviderPolicy.java`
- Modify `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiProviderConfiguration.java`
- Modify `src/main/java/com/ford/service/ai/configuration/AiProperties.java`
- Create `src/main/java/com/ford/service/channels/application/port/out/MetaMediaRetrievalPort.java`
- Create `src/main/java/com/ford/service/channels/adapter/out/meta/MetaMediaRestClientAdapter.java`
- Create `src/main/java/com/ford/service/ai/application/InboundMediaInterpretationPort.java`
- Create `src/main/java/com/ford/service/ai/application/InboundContentResolutionService.java`
- Create `src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiInboundMediaInterpretationAdapter.java`
- Test `src/test/java/com/ford/service/ai/ModelProviderPolicyTest.java`
- Create `src/test/java/com/ford/service/ai/InboundContentResolutionServiceTest.java`
- Create `src/test/java/com/ford/service/channels/MetaMediaRestClientAdapterTest.java`

**Interfaces:**

```java
public interface MetaMediaRetrievalPort {
    RetrievedMedia retrieve(UUID tenantId, String providerMediaId, long maxBytes);
}

public interface InboundMediaInterpretationPort {
    String interpret(String providerId, InboundMessageKind kind,
                     Resource media, String declaredMimeType, String caption);
}

public record ResolvedInboundContent(String text, InboundContentStatus status) {}

public ResolvedInboundContent InboundContentResolutionService.resolve(
        AcceptedInboundMessage inbound, UUID tenantId, UUID conversationId);
```

`RetrievedMedia` is an in-memory/temporary resource with verified MIME type and measured size; it must be `AutoCloseable` so cleanup is guaranteed in try-with-resources. A provider is eligible only if its ID is in `ford.ai.media.approved-providers`, its ID is also in the relevant `ford.ai.media.image-providers` or `ford.ai.media.audio-providers` list, and its configured runtime descriptor is healthy and supports that modality. All three lists default empty and `ford.ai.media.enabled` defaults false. No candidate means safe clarification, not a cloud/local bypass. Media approval explicitly covers raw customer media that may contain sensitive information. Meta media retrieval validates tenant binding and accepts only opaque provider media IDs; it never accepts a URL from webhook content.

- [ ] **Step 1: Write failing provider policy tests** for image/audio candidate selection, incapable provider rejection, unapproved/unhealthy provider rejection, and sensitive-data restriction. Preserve the current three-boolean `ProviderCapabilities` constructor with new modality flags defaulting to false.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.ford.service.ai.ModelProviderPolicyTest'`. Expected: modality requirements are not modeled.
- [ ] **Step 3: Implement explicit modality capability filtering** in the existing provider policy/configuration. Add no implicit capability defaults for OpenRouter-compatible endpoints.
- [ ] **Step 4: Write failing resolution tests** for plain text, image caption+description, audio transcript, interactive choice, location, unsupported document/video/sticker, missing media, over-limit media, failed provider call, and resource cleanup. Verify no call to the interpreter occurs for unsupported or unapproved media.
- [ ] **Step 5: Implement `InboundContentResolutionService`** with an initial maximum media size of 10 MiB and an 8-second combined download+interpretation timeout, both configurable; enforce the existing 30-second overall turn deadline. For image use Spring AI `UserMessage` media only on an explicitly image-capable approved chat provider. For audio use Spring AI `TranscriptionModel` only if its concrete provider is approved and supports the validated WhatsApp audio type; otherwise return the Spanish clarification `No pude interpretar ese archivo con seguridad. ¿Podría describirme lo que necesita o enviarlo en un mensaje de texto?`. Do not transcode or add native binaries in this plan.
- [ ] **Step 6: Implement Meta retrieval** behind `MetaMediaRetrievalPort` with injected Spring `RestClient`, authenticated requests, status/timeout/content-length checks, streaming byte cap, MIME verification, and redacted error logs. Never log or persist Graph media URLs or tokens.
- [ ] **Step 7: Run** `./gradlew test --tests 'com.ford.service.ai.ModelProviderPolicyTest' --tests 'com.ford.service.ai.InboundContentResolutionServiceTest' --tests 'com.ford.service.channels.MetaMediaRestClientAdapterTest'`. Expected: all gates/fallback/cleanup tests pass.
- [ ] **Step 8: Commit** as `feat(ai): interpret approved WhatsApp image and audio input` with only Task 4 files.

Spring AI 2.0.1 provides user-message media parts for supported multimodal chat models and a `TranscriptionModel` abstraction. Use the APIs from the [Spring AI Multimodality reference](https://docs.spring.io/spring-ai/reference/api/multimodality.html) and [Transcription API](https://docs.spring.io/spring-ai/reference/api/audio/transcriptions.html); verify actual provider/model/MIME capability against resolved dependencies and current configured approvals before wiring.

## Task 5: Resolve content before memory and assistant invocation

**Files:**
- Modify `src/main/java/com/ford/service/conversations/application/DefaultConversationTurnProcessor.java`
- Modify `src/main/java/com/ford/service/conversations/application/ConversationConfiguration.java`
- Modify `src/main/java/com/ford/service/channels/api/AcceptedInboundMessage.java` only if Task 3 did not finalize its contract
- Test `src/test/java/com/ford/service/conversations/DefaultConversationTurnProcessorTest.java`
- Extend `src/integrationTest/java/com/ford/service/channels/MetaInboundWorkflowIntegrationTest.java`

**Interfaces:** Inject `InboundContentResolutionService` into the graph turn processor. Resolve the accepted message before `ConversationMemoryService.recordInbound`, `load`, and `ConversationAssistant.respond`. For interpreted content, persist the normalized transcript/description using `persistResolvedContent` before memory insertion; it becomes canonical text for transcript, memory, classification, and answering. Media bytes are already closed. Unsupported/failed media returns a typed safe-response status and bypasses answer-model/provider calls while still committing one normal bot reply through the existing commit/outbox path.

- [ ] **Step 1: Write failing processor tests** asserting content resolution occurs before memory write/load and assistant call; supported image/audio uses resolved text; unsupported/failed media commits one safe clarification and does not call the assistant; plain text behavior remains unchanged.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.ford.service.conversations.DefaultConversationTurnProcessorTest'`. Expected: the processor currently passes raw stored text directly.
- [ ] **Step 3: Implement the smallest processor/configuration change** using the resolver and existing turn commit service. Do not add a second persistence or delivery mechanism.
- [ ] **Step 4: Run focused processor/workflow tests.** Expected: ordering and exactly-once commit tests pass.
- [ ] **Step 5: Commit** as `feat(conversations): resolve typed inbound content before turns` with only Task 5 files.

## Task 6: End-to-end safety, observability, docs, and verification

**Files:**
- Extend `src/integrationTest/java/com/ford/service/e2e/MetaToOutboxVerticalFlowTest.java`
- Extend `src/integrationTest/java/com/ford/service/channels/MetaInboundWorkflowIntegrationTest.java`
- Modify `src/main/java/com/ford/service/channels/application/ChannelMessageService.java` only for non-content structured outcome logs if needed
- Modify `src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java` only for non-content classification/media timing fields if needed
- Modify `docs/operations.md`
- Modify `src/main/resources/application.yml` only for validated safe defaults and documented configurable limits

- [ ] **Step 1: Add integration tests** for one multi-message webhook, duplicate redelivery, image/audio resolved flow, unsupported media, classifier low-confidence fallback, and exactly one outbound record per provider message ID.
- [ ] **Step 2: Add privacy assertions** proving captured application logs do not contain sample user text, audio transcript, image URL, provider access token, or prompt. Assert logs include conversation/receipt ID, transport kind, intent/tool family, provider attempt/fallback category, and delivery outcome only.
- [ ] **Step 3: Implement minimal structured logs and operations docs.** Document byte/timeout defaults, capability configuration, supported formats, no-media-storage behavior, and what clarification the user sees on failed/unsupported media. Keep credentials in existing secret management; no `.env`, inventory secret, or deployment secret edits in this feature.
- [ ] **Step 4: Run unit suite.** Run `./gradlew test`; expected: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Run integration suite.** Run `./gradlew integrationTest`; expected: `BUILD SUCCESSFUL`, all Testcontainers-backed channel/conversation tests pass. If Docker is unavailable, inspect the configured Colima profile and report the specific infrastructure failure; do not change project configuration or reset data.
- [ ] **Step 6: Run full verification.** Run `./gradlew check`; expected: `BUILD SUCCESSFUL`.
- [ ] **Step 7: Inspect diff and migration.** Run `git diff --check` and verify Liquibase changes are additive, no raw media persistence/logging exists, and no unrelated user edits are staged.
- [ ] **Step 8: Commit** as `test(channels): verify WhatsApp message understanding flow` with only Task 6 files. Deployment remains a separate user-approved action after local verification and review.

## Dependencies and ordering

```text
Task 1 (semantic categories and safe routing)
Task 2 (typed parser) -> Task 3 (batch durable receipt)
Task 3 + Task 1 -> Task 4 (media capability and resolver)
Task 4 -> Task 5 (turn processor integration)
Tasks 1–5 -> Task 6 (end-to-end verification/docs)
```

## Definition of Done

- [ ] All Tasks 1–6 complete with focused tests written before implementation.
- [ ] Every supported WhatsApp inbound entry is normalized and idempotently persisted; no envelope message is silently lost.
- [ ] Audio/images are interpreted only by already approved, explicitly capable providers under configured byte/time limits; unsupported media gets a safe clarification.
- [ ] Existing text, Instagram, and Messenger flows remain compatible.
- [ ] Semantic categories do not become lead-temperature labels; tool authorization matches the classified family and remains tenant-scoped.
- [ ] Privacy assertions and migration compatibility pass.
- [ ] `./gradlew test`, `./gradlew integrationTest`, and `./gradlew check` pass, or an infrastructure blocker is reported with evidence.
- [ ] No database reset or deployment occurred without separate approval.

## Self-review

- **Spec coverage:** Transport kinds, multi-message webhook handling, idempotency, media interpretation/limits/cleanup, approved-provider gating, intent taxonomy, safe classifier fallback, tool authorization, privacy, and end-to-end verification each map to Tasks 1–6.
- **Placeholder scan:** No TODO/TBD implementation steps. Migration 018 follows the current latest migration 017 verified during planning.
- **Type consistency:** `ChannelAdapter.parseInboundMessages`, `InboundMessageKind`, `InboundAttachment`, `MetaMediaRetrievalPort`, `InboundMediaInterpretationPort`, `ResolvedInboundContent`, and `InboundContentResolutionService.resolve` are defined where introduced and consumed in subsequent tasks.
- **Scope check:** Media OCR/documents and video analysis remain explicitly excluded. Provider approvals, Ford MCP data, and handoff policy remain unchanged.
