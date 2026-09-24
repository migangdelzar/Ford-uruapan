# Runtime System Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load the Ford Uruapan system prompt from a Markdown seed or database override and update it safely through an authenticated API without restarting.

**Architecture:** A JDBC repository stores one active prompt document with optimistic versioning. A prompt service falls back to the classpath Markdown seed and publishes a domain event after updates. The Spring AI adapter asks the service for the current prompt on every synchronous completion.

**Tech Stack:** Spring Boot MVC, Spring AI `ChatClient`, Spring JDBC `JdbcClient`, Liquibase, Spring Modulith events, JUnit 5.

## Global Constraints

- Do not add WebFlux or Spring AI streaming.
- Do not log prompt content.
- Use `PUT` for replacing the complete prompt document.
- Require `ROLE_OPERATOR` for prompt management for the current single-tenant deployment.
- Preserve unrelated working-tree changes.

### Task 1: Add prompt seed, persistence schema, and repository

**Files:**
- Create: `ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/SystemPromptSnapshot.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/port/out/SystemPromptRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/JdbcSystemPromptRepository.java`
- Create: `ford-ai-service/src/main/resources/db/changelog/changes/005-ai-system-prompt.yaml`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptRepositoryTest.java`

- [ ] **Step 1: Add the failing repository contract test**

Test that an empty repository returns no prompt and a successful replacement returns version `1`.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./gradlew test --tests com.ford.service.ai.SystemPromptRepositoryTest --no-scan`.
Expected: compilation failure because the prompt repository types do not exist.

- [ ] **Step 3: Add the prompt table and repository**

Create a singleton `ai_system_prompt` row with `content`, `version`, `updated_by`, and `updated_at`. Implement JDBC optimistic replacement using `expectedVersion`.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same command; expected result is `BUILD SUCCESSFUL`.

### Task 2: Add prompt service, event, and Spring AI integration

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/application/SystemPromptService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/domain/SystemPromptChanged.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptServiceTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SpringAiChatCompletionAdapterTest.java`

- [ ] **Step 1: Write failing service tests**

Cover Markdown fallback, version `1` replacement, and stale-version rejection.

- [ ] **Step 2: Run focused tests and verify they fail**

Run `./gradlew test --tests com.ford.service.ai.SystemPromptServiceTest --no-scan`.
Expected: compilation failure because the service does not exist.

- [ ] **Step 3: Implement the service and event**

Load the classpath seed once, use the repository override when present, enforce the 32,000-character limit, and publish `SystemPromptChanged` only after a successful update.

- [ ] **Step 4: Inject the service into the synchronous AI adapter**

Replace the inline `.system(...)` literal with `.system(promptService.currentContent())`; preserve `.call()` and all existing provider behavior.

- [ ] **Step 5: Run focused tests and verify they pass**

Run service and adapter tests; expected result is `BUILD SUCCESSFUL`.

### Task 3: Add authenticated operator API

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/web/SystemPromptController.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/api/SystemPromptResponse.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/api/SystemPromptUpdateRequest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/SystemPromptControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

Cover `GET`, successful `PUT`, stale-version conflict, blank-content validation, and anonymous rejection.

- [ ] **Step 2: Run focused tests and verify they fail**

Run `./gradlew test --tests com.ford.service.ai.SystemPromptControllerTest --no-scan`.
Expected: compilation failure because the controller does not exist.

- [ ] **Step 3: Implement the API**

Expose `GET` and `PUT /api/v1/operator/ai/system-prompt`; return version metadata but never log prompt content. Return `409 Conflict` for stale versions and `400 Bad Request` for invalid content.

- [ ] **Step 4: Secure the route**

Require `ROLE_OPERATOR` for `/api/v1/operator/ai/**`.

- [ ] **Step 5: Run focused tests and verify they pass**

Run controller/security tests; expected result is `BUILD SUCCESSFUL`.

### Task 4: Update docs and verify

**Files:**
- Modify: `ford-ai-service/docs/api.md`
- Modify: `ford-ai-service/docs/operations.md`
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/database/SchemaIntegrationTest.java`

- [ ] **Step 1: Document API and operations**

Document the seed location, `GET`/`PUT` contract, optimistic version field, and rollback behavior.

- [ ] **Step 2: Verify all tests**

Run `./gradlew test integrationTest --no-scan`.

- [ ] **Step 3: Review and commit**

Run `git diff --check`, stage only prompt feature files, and commit with `feat: add runtime system prompt management`.
