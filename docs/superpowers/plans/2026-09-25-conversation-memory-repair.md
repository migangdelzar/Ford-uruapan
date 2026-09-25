# Conversation Memory Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each successful assistant turn durable, idempotent, and clearly available as historical context to the next AI turn.

**Architecture:** PostgreSQL remains the canonical transcript store and Redis remains only a cache. The conversation workflow records the assistant response after optimistic turn completion succeeds, using a deterministic provider message identifier derived from the conversation and inbound provider message. Prompt assembly keeps the existing provider-neutral memory string but adds explicit historical/current-turn boundaries and tests the resulting behavior.

**Tech Stack:** Java, Spring Boot, Spring Modulith events, Spring JDBC, PostgreSQL, Redis, JUnit 5, Mockito, Gradle.

## Global Constraints

- Preserve tenant/channel/provider-account/participant conversation isolation.
- Preserve webhook acknowledgement and existing optimistic conversation-version behavior.
- Assistant memory writes are fail-open and must not prevent outbound delivery publication.
- Duplicate inbound processing must not create duplicate assistant transcript rows.
- Do not enable semantic memory or introduce model-generated summaries in this repair.
- Do not log raw message contents or secrets.
- Preserve unrelated working-tree changes.

## File Map

- Modify `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationMemoryService.java` to expose a failure-tolerant outbound recording operation.
- Modify `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationWorkflow.java` to persist the accepted assistant turn before publishing delivery.
- Modify `ford-ai-service/src/main/java/com/ford/service/conversations/application/ConversationAssistantAdapter.java` to frame memory as historical context with an explicit current-turn boundary.
- Modify `ford-ai-service/src/test/java/com/ford/service/conversations/ConversationWorkflowTest.java` to assert assistant transcript persistence and idempotent identity.
- Modify `ford-ai-service/src/test/java/com/ford/service/ai/ConversationAssistantAdapterTest.java` to assert memory is passed as structured historical context.
- Modify `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java` to assert the model prompt distinguishes history from current input.
- Add `docs/superpowers/specs/2026-09-25-conversation-memory-repair-design.md` documenting the observed gap and chosen boundaries.

### Task 1: Prove the missing assistant transcript

- [x] Add a workflow test asserting that a successful bot turn calls `recordOutbound` with direction `OUTBOUND`, the reply text, and a stable `assistant:<conversation-id>:<inbound-provider-id>` provider message ID.
- [x] Run the focused test and confirm it fails because the current workflow only invalidates memory.

### Task 2: Implement durable outbound recording

- [x] Add `ConversationMemoryService.recordOutbound(...)`, applying the same enabled/content guards and fail-open logging used by inbound recording.
- [x] After `completeBotTurn` returns `true`, call `recordOutbound` with a deterministic UUID and provider message ID derived from the conversation and inbound provider message ID, then invalidate the cache and publish delivery.
- [x] Run the focused workflow and memory-service tests.

### Task 3: Make memory framing explicit

- [x] Add a failing adapter test for the new historical-context envelope and the explicit instruction that the current user message is authoritative.
- [x] Update `ConversationAssistantAdapter.memoryPrompt` with stable labels, chronological history, and no duplicate current message insertion.
- [x] Update Spring AI prompt assertions and run the focused AI tests.

### Task 4: Verify and deploy

- [x] Run the focused tests, full Gradle test suite, and existing model-agnostic harness.
- [x] Build and push a new GHCR image through the existing Mise task.
- [x] Deploy through the existing Ansible/Mise mechanism with the current inventory and MCP/OpenRouter settings.
- [x] Verify container health, memory-related startup configuration, MCP handshake, and webhook acceptance on `emme-1`; verify PostgreSQL/Redis health on `emme-2`.
