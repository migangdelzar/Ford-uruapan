# Ford MCP Information Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an authenticated synchronous Spring AI MCP client so current Ford catalog and offer information comes from MCP, with a safe fail-closed path when MCP is unavailable.

**Architecture:** Use Spring AI 2.0.1's standard MCP client starter and Streamable HTTP auto-configuration. The MCP callbacks join the existing read-only `ToolAuthorizationPolicy`; volatile vehicle/offer tool families are routed to MCP tools only, while Redis/PostgreSQL conversation memory and approved local knowledge remain unchanged.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.1 BOM, Spring MVC, synchronous MCP client, JUnit 5, Gradle Kotlin DSL.

## Global Constraints

- Do not add WebFlux or Spring AI streaming dependencies.
- Keep `FORD_MCP_ENABLED=false` as the default for local startup.
- `FORD_MCP_URL` is the base URL and `FORD_MCP_ENDPOINT` is `/mcp`.
- Never log or expose `FORD_MCP_TOKEN`.
- MCP is authoritative for volatile Ford information; do not silently fall back to PostgreSQL for volatile answers.
- Preserve the existing Redis hot-memory and PostgreSQL durable-memory flow.
- Every implementation task must add or update a focused test before production code.

---

### Task 1: Add MCP dependency and configuration contract

**Files:**
- Modify: `ford-ai-service/gradle/libs.versions.toml`
- Modify: `ford-ai-service/build.gradle.kts`
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/McpProperties.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/.env.example`
- Modify: `ford-ai-service/.env.prod.example`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/configuration/McpPropertiesTest.java`

**Interfaces:**
- Produces a validated `McpProperties` record/configuration bean with `enabled`, `url`, `endpoint`, `token`, `requestTimeout`, and immutable `readOnlyToolNames`.
- Uses `org.springframework.ai:spring-ai-starter-mcp-client` only; the existing Spring AI BOM supplies its version.

- [ ] **Step 1: Write the failing test**

```java
@Test
void rejectsEnabledMcpWithoutUrlOrToken() {
    assertThatThrownBy(() -> McpProperties.validate(true, "", "/mcp", "", Duration.ofSeconds(30), Set.of("search_vehicles")))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void acceptsDisabledMcpWithoutCredentials() {
    assertThatCode(() -> McpProperties.validate(false, "", "/mcp", "", Duration.ofSeconds(30), Set.of("search_vehicles")))
            .doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.ford.service.ai.configuration.McpPropertiesTest`

Expected: FAIL because `McpProperties` does not yet exist.

- [ ] **Step 3: Write minimal implementation**

Add the property binding under `ford.mcp` and map these environment variables in `application.yml`:

```yaml
ford:
  mcp:
    enabled: ${FORD_MCP_ENABLED:false}
    url: ${FORD_MCP_URL:http://127.0.0.1:3001}
    endpoint: ${FORD_MCP_ENDPOINT:/mcp}
    token: ${FORD_MCP_TOKEN:}
    request-timeout: ${FORD_MCP_REQUEST_TIMEOUT:30s}
    required-for-volatile-information: ${FORD_MCP_REQUIRED_FOR_VOLATILE_INFORMATION:true}
```

Register the starter dependency and bind the properties with `@ConfigurationProperties(prefix = "ford.mcp")`. Normalize the endpoint to begin with `/`; when enabled, reject blank URL/token and non-positive timeout.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.ford.service.ai.configuration.McpPropertiesTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ford-ai-service/gradle ford-ai-service/build.gradle.kts \
  ford-ai-service/src/main/java/com/ford/service/ai/configuration/McpProperties.java \
  ford-ai-service/src/main/resources/application.yml \
  ford-ai-service/.env.example ford-ai-service/.env.prod.example \
  ford-ai-service/src/test/java/com/ford/service/ai/configuration/McpPropertiesTest.java
git commit -m "feat(ai): add MCP client configuration"
```

### Task 2: Wire authenticated synchronous Streamable HTTP MCP callbacks

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/mcp/FordMcpConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/adapter/out/mcp/FordMcpConfigurationTest.java`

**Interfaces:**
- Produces an optional synchronous MCP callback provider for the named connection `ford-uruapan`.
- Sends `Authorization: Bearer <FORD_MCP_TOKEN>` through the supported HTTP transport customizer.
- Produces no MCP callbacks when disabled or not configured.

- [ ] **Step 1: Write the failing test**

Test the configuration with a fake/customizer capture and assert that the authorization header is set only for `ford-uruapan`; assert that disabled configuration yields no callbacks.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.ford.service.ai.adapter.out.mcp.FordMcpConfigurationTest`

Expected: FAIL because the MCP configuration does not exist.

- [ ] **Step 3: Write minimal implementation**

Configure the named connection using Spring AI properties:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${FORD_MCP_ENABLED:false}
        name: ford-advisor
        version: 1.0.0
        initialized: true
        type: SYNC
        request-timeout: ${FORD_MCP_REQUEST_TIMEOUT:30s}
        toolcallback:
          enabled: true
        streamable-http:
          connections:
            ford-uruapan:
              url: ${FORD_MCP_URL:http://127.0.0.1:3001}
              endpoint: ${FORD_MCP_ENDPOINT:/mcp}
```

Implement the version-compatible Spring AI MCP customizer against the resolved 2.0.1 API. If the API exposes `HttpClientStreamableHttpTransport.Builder`, customize its request header; if it exposes the 2.0.1 request customizer interface, use that supported extension point instead. Do not create a hand-rolled HTTP client.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.ford.service.ai.adapter.out.mcp.FordMcpConfigurationTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/mcp \
  ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java \
  ford-ai-service/src/main/resources/application.yml \
  ford-ai-service/src/test/java/com/ford/service/ai/adapter/out/mcp/FordMcpConfigurationTest.java
git commit -m "feat(ai): connect Spring AI to Ford MCP"
```

### Task 3: Merge and authorize MCP tools safely

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/ToolAuthorizationPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DecisionPolicy.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleTools.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/application/ToolAuthorizationPolicyTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/application/DecisionPolicyTest.java`

**Interfaces:**
- `ToolAuthorizationPolicy` receives local callbacks and MCP callbacks separately, plus the configured volatile-source state.
- `DecisionPolicy.allowedToolNames` maps vehicle/inventory/offer families to MCP names when MCP is available.
- The volatile MCP allow list is exactly: `search_vehicles`, `get_vehicle`, `compare_vehicles`, `search_offers`, `list_promotions`, `get_vehicle_provenance`, `get_catalog_status`.

- [ ] **Step 1: Write the failing tests**

Add tests that prove:

```java
assertThat(policy.readOnlyTools(context, Set.of("search_vehicles", "send_message")))
        .extracting(tool -> tool.getToolDefinition().name())
        .containsExactly("search_vehicles");

assertThat(new DecisionPolicy().allowedToolNames(vehicleDecision, availableNames))
        .contains("search_vehicles", "get_vehicle", "compare_vehicles");
```

Add a fail-closed test proving a volatile decision returns no local PostgreSQL vehicle callback when MCP is required but unavailable.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.ford.service.ai.application.ToolAuthorizationPolicyTest --tests com.ford.service.ai.application.DecisionPolicyTest`

Expected: FAIL because current policy only knows the local `vehicle_snapshot_search` callback.

- [ ] **Step 3: Write minimal implementation**

Filter MCP callbacks by the configured read-only allow list, keep the existing denied-term filter, and make the volatile tool-family mapping source-aware. When MCP is required but unavailable, remove volatile catalog/offer callbacks from the model request; leave approved knowledge and non-volatile tools unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.ford.service.ai.application.ToolAuthorizationPolicyTest --tests com.ford.service.ai.application.DecisionPolicyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ford-ai-service/src/main/java/com/ford/service/ai/application \
  ford-ai-service/src/main/java/com/ford/service/ai/configuration/SpringAiConfiguration.java \
  ford-ai-service/src/main/java/com/ford/service/ai/adapter/in/tool/VehicleTools.java \
  ford-ai-service/src/test/java/com/ford/service/ai/application
git commit -m "feat(ai): authorize Ford MCP information tools"
```

### Task 4: Implement transparent fail-closed responses and observability

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapter.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/SpringAiAssistantService.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/adapter/out/springai/SpringAiChatCompletionAdapterTest.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/ai/application/DefaultAssistantOrchestratorTest.java`

**Interfaces:**
- Volatile MCP failures are represented internally as a bounded source-unavailable outcome.
- Customer output uses the existing safe fallback/handoff wording and never includes endpoint, token, stack trace, or stale catalog values.
- Logs contain only provider, source, operation category, exception type, and latency; token and message content remain excluded.

- [ ] **Step 1: Write the failing tests**

Add tests for disabled MCP, connection failure, empty result, and successful tool callback execution. Assert that failure output contains no URL/token and that local PostgreSQL vehicle values are absent from the response path.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapterTest --tests com.ford.service.ai.application.DefaultAssistantOrchestratorTest`

Expected: FAIL because the current adapter neither distinguishes MCP source failure nor enforces the volatile fail-closed result.

- [ ] **Step 3: Write minimal implementation**

Add a source-unavailable instruction only for volatile decisions and catch MCP transport/tool failures at the application boundary. Preserve the existing provider fallback for general failures, but use the safe source-unavailable response for volatile Ford-information requests. Record a Micrometer timer/counter for MCP calls and failures with bounded low-cardinality tags.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.ford.service.ai.adapter.out.springai.SpringAiChatCompletionAdapterTest --tests com.ford.service.ai.application.DefaultAssistantOrchestratorTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ford-ai-service/src/main/java/com/ford/service/ai/adapter/out/springai \
  ford-ai-service/src/main/java/com/ford/service/ai/application \
  ford-ai-service/src/test/java/com/ford/service/ai/adapter/out/springai \
  ford-ai-service/src/test/java/com/ford/service/ai/application
git commit -m "feat(ai): fail closed when Ford MCP is unavailable"
```

### Task 5: Add MCP integration verification and deployment configuration

**Files:**
- Create: `ford-ai-service/src/integrationTest/java/com/ford/service/ai/mcp/FordMcpStreamableHttpIntegrationTest.java`
- Modify: `ford-ai-service/src/test/resources/application-test.yml`
- Modify: `deployment/runtime/app/.env.template`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `docs/frontend-system-prompt-flow.md`
- Modify: `docs/observability.md`

**Interfaces:**
- A fake local MCP HTTP server responds to initialize and tools/list/tools/call requests.
- The integration test verifies `POST /mcp`, the bearer header, one Ford information tool callback, and safe behavior when the server is unavailable.

- [ ] **Step 1: Write the failing integration test**

Start a fake Streamable HTTP server on a dynamic port, configure `FORD_MCP_URL` and a test token, invoke the application MCP callback, and assert the request path and `Authorization` header. Add a second test that stops the server and asserts the volatile fail-closed result.

- [ ] **Step 2: Run the integration test to verify it fails**

Run: `./gradlew integrationTest --tests com.ford.service.ai.mcp.FordMcpStreamableHttpIntegrationTest`

Expected: FAIL until the MCP client and callback wiring are complete.

- [ ] **Step 3: Write the minimum integration/deployment implementation**

Set production templates to use:

```text
FORD_MCP_ENABLED=false
FORD_MCP_URL=https://change-ide-nurses-brochure.trycloudflare.com
FORD_MCP_ENDPOINT=/mcp
FORD_MCP_TOKEN=replace-with-mcp-http-bearer-token
FORD_MCP_REQUEST_TIMEOUT=30s
FORD_MCP_REQUIRED_FOR_VOLATILE_INFORMATION=true
```

Keep the production default disabled until the token and stable MCP URL are injected by the deployment secret mechanism. Document the enablement and verification command without copying secrets into Git.

- [ ] **Step 4: Run the complete verification suite**

Run: `./gradlew check`

Expected: `BUILD SUCCESSFUL`, zero failed tests, and the MCP integration test passes with the fake endpoint.

- [ ] **Step 5: Commit**

```bash
git add ford-ai-service/src/integrationTest ford-ai-service/src/test/resources \
  deployment/runtime/app/.env.template \
  deployment/ansible/roles/app/templates/app.env.j2 \
  docs/frontend-system-prompt-flow.md docs/observability.md
git commit -m "test(ops): verify Ford MCP deployment contract"
```

## Plan Self-Review

- **Spec coverage:** Dependency, property validation, authenticated transport, callback authorization, fail-closed behavior, observability, integration tests, and deployment templates are covered by Tasks 1–5.
- **Placeholder scan:** No implementation step relies on an unspecified file, endpoint, secret, or test command.
- **Type consistency:** The plan uses the existing `ToolCallback`, `ToolAuthorizationPolicy`, `DecisionPolicy`, `ConversationDecision`, and Spring AI 2.0.1 BOM. The exact MCP customizer type is verified during Task 2 compilation because Spring AI 2.0.1 exposes the supported transport extension point.
- **Scope:** No WebFlux, streaming response, memory, or MCP-server changes are included.

