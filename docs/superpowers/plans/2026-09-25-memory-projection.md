# Conversation Memory Projection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep a bounded, deterministic conversation summary and a small set of explicitly stated customer facts synchronized after each accepted assistant turn.

**Architecture:** A pure application projector derives only from the latest inbound user message and the existing durable facts. The workflow writes the projection with optimistic memory-version control after the outbound transcript row is accepted. Projection failures never affect delivery.

**Tech Stack:** Java, Spring Boot, Spring JDBC, PostgreSQL, JUnit 5, Gradle, Mise, Ansible.

## Global Constraints

- Exact transcript remains canonical; projection is an optimization for context limits.
- Never infer or model-generate facts in this pass.
- Store only explicit name, location, preference, vehicle-interest, or budget statements.
- Bound summary and facts with existing `MemoryProperties` limits.
- Projection failures and optimistic conflicts are fail-open.
- Preserve tenant isolation, webhook acknowledgement, and outbound delivery ordering.

### Task 1: Add red tests for projection behavior

- [x] Test that an explicit user statement produces bounded facts and a latest-request summary.
- [x] Test that the workflow invokes projection after a successful bot turn with the loaded memory version.
- [x] Run focused tests and confirm they fail because no projector/write call exists.

### Task 2: Implement deterministic projection

- [x] Add a pure `ConversationMemoryProjector` with conservative Spanish/English patterns, latest-value replacement by fact kind, and configured bounds.
- [x] Add fail-open projection methods to `ConversationMemoryService`.
- [x] Call projection from `ConversationWorkflow` after recording the assistant turn and before cache invalidation/delivery publication.
- [x] Run focused tests.

### Task 3: Verify and redeploy

- [x] Run the full Gradle suite, chat harness, and diff checks.
- [x] Publish a new GHCR image through Mise.
- [x] Deploy with Ansible to `emme-1` and verify `emme-2` data services remain healthy.
- [x] Verify application health, MCP handshake, and startup memory configuration.
