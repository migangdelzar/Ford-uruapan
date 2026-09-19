# Ford Service CI/CD and Langfuse Observability Implementation Plan

**Status: Complete**

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add repository-local enterprise GitHub Actions, self-hosted Langfuse/OpenTelemetry deployment, Spring AI tracing, PII-safe AI evaluation artifacts, and operational documentation.

**Architecture:** Keep the Ford application, pipeline, and observability deployment in one repository. Use local reusable GitHub workflows, a separate Compose project for Langfuse, OpenTelemetry as the telemetry contract, and Langfuse as the AI trace/evaluation backend. Keep telemetry export asynchronous and fail-open so Langfuse cannot block customer interactions.

**Tech Stack:** GitHub Actions, Java 25, Gradle, Spring Boot 4.1.0, Spring AI 2.0.1, Spring Boot OpenTelemetry starter, Micrometer Observation/Tracing, OpenTelemetry Collector, self-hosted Langfuse v4, PostgreSQL, ClickHouse, Redis/Valkey, MinIO/S3-compatible storage, GHCR, SBOM/provenance attestations, Testcontainers, Node.js built-in test runner, and Docker Compose.

## Global Constraints

- Runtime is Spring MVC with Java 25 virtual threads; WebFlux is not part of MVP.
- Spring AI is used for `ChatClient`, structured output, advisors, RAG, and tools.
- PostgreSQL is the sole primary application database; Redis stores only temporary state, locks, limits, and cache.
- Spring configuration is YAML-only; do not add `.properties` configuration files.
- Spring AI prompt/completion capture is disabled by default in production.
- No raw customer payloads, prompts, completions, phone numbers, names, email addresses, government IDs, bank data, or exact income may leave the application unredacted.
- Workflow credentials use least privilege and OIDC where the deployment target supports it.
- Pull requests never receive live model credentials and never publish production artifacts.
- CI results are always uploaded on success and failure.
- No Git submodules and no separate domain microservices.
- Write a failing test or contract check before each implementation task; configuration-only changes may use executable contract tests as the red phase.
- Use `JdbcClient`/SQL for application persistence and preserve existing ports/adapters.

---

## File Map

### GitHub Actions and validation

- Create: `.github/actions/setup-java-gradle/action.yml` — Java 25 and Gradle cache setup.
- Create: `.github/workflows/ci.yml` — event entrypoint and promotion order.
- Create: `.github/workflows/reusable-java-ci.yml` — reusable quality, test, integration, and summary jobs.
- Create: `.github/workflows/reusable-security.yml` — secret, dependency, and SARIF security jobs.
- Create: `.github/workflows/reusable-container.yml` — image, SBOM, scan, push, and attestation jobs.
- Create: `.github/workflows/reusable-observability.yml` — Compose/config validation and redacted telemetry smoke jobs.
- Create: `.github/workflows/reusable-deploy.yml` — protected environment deployment contract and smoke-test hook.
- Create: `scripts/workflow-contract.test.mjs` — static workflow contract test.
- Create: `scripts/observability-contract.test.mjs` — Compose and collector contract test.

### AI evaluation

- Create: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/AiEvaluationResult.java` — deterministic evaluation result record.
- Create: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/JsonlAiEvaluationReportWriter.java` — test report writer.
- Create: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/AiRegressionEvaluationTest.java` — fixture execution and blocking deterministic gates.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/Intent.java` — add the explicit lead-handoff intent used by the fixture gate.
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/IntentRouter.java` — route explicit advisor/purchase requests.
- Modify: `ford-ai-service/src/test/java/com/ford/service/ai/IntentRouterTest.java` — regression coverage for lead-handoff routing.
- Modify: `ford-ai-service/src/test/resources/fixtures/ai/spanish-vehicle-finance-cases.jsonl` — add stable case identifiers and expected safety/tool assertions.

### Application observability

- Modify: `ford-ai-service/gradle/libs.versions.toml` — add the Spring Boot OpenTelemetry starter alias.
- Modify: `ford-ai-service/build.gradle.kts` — add the BOM-managed tracing dependency.
- Modify: `ford-ai-service/src/main/resources/application.yml` — OTLP endpoint, sampling, resource, and Spring AI observation settings.
- Create: `ford-ai-service/src/test/resources/application.yml` — disable external export and use deterministic test tracing.
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/AiObservationService.java` — bounded custom observation boundary for business steps.
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/DefaultAiObservationService.java` — `ObservationRegistry` implementation.
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/PiiObservationFilter.java` — redacts observation values before export.
- Create: `ford-ai-service/src/test/java/com/ford/service/observability/AiObservationServiceTest.java` — custom span and bounded-tag behavior.
- Create: `ford-ai-service/src/test/java/com/ford/service/observability/PiiObservationFilterTest.java` — prompt/contact/financial redaction behavior.

### Self-hosted observability

- Create: `deployment/observability/langfuse/docker-compose.yml` — pinned Langfuse v4 pilot stack.
- Create: `deployment/observability/langfuse/.env.example` — non-secret configuration contract.
- Create: `deployment/observability/langfuse/.gitignore` — local secrets and persistent local state exclusions.
- Create: `deployment/observability/langfuse/README.md` — startup, health, persistence, backup, upgrade, and retention instructions.
- Create: `deployment/observability/otel-collector/config.yaml` — OTLP receiver, redaction processor, sampling, and Langfuse exporter.
- Create: `deployment/observability/otel-collector/README.md` — collector configuration and failure behavior.
- Create: `deployment/observability/dashboards/ford-service-overview.json` — bounded application/AI dashboard definition.

### Documentation

- Create: `docs/ci-cd.md` — workflow triggers, gates, artifacts, environments, and promotion rules.
- Create: `docs/observability.md` — trace model, Langfuse operations, privacy, retention, and incident response.
- Modify: `ford-ai-service/README.md` — link local pipeline and observability commands.

---

## Task 1: Add executable workflow contracts and reusable Java CI

**Files:**

- Create: `.github/actions/setup-java-gradle/action.yml`
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/reusable-java-ci.yml`
- Create: `scripts/workflow-contract.test.mjs`

**Interfaces:**

- `ci.yml` calls `reusable-java-ci.yml` with `workflow_call` inputs `run-integration` and `run-ai-evaluation`.
- `reusable-java-ci.yml` produces job results named `quality`, `unit`, `integration`, `evaluation`, and `summary`.
- Every reusable workflow declares `permissions`, `timeout-minutes`, and `concurrency`.

- [x] **Step 1: Write the failing workflow contract test**

Create a Node built-in test that reads `.github/workflows/*.yml` and asserts:

```js
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const required = [
  ".github/workflows/ci.yml",
  ".github/workflows/reusable-java-ci.yml",
  ".github/workflows/reusable-security.yml",
  ".github/workflows/reusable-container.yml",
  ".github/workflows/reusable-observability.yml",
  ".github/workflows/reusable-deploy.yml"
];

test("required workflow contracts exist", async () => {
  for (const path of required) {
    await assert.doesNotReject(() => readFile(path, "utf8"), path);
  }
});
```

Add assertions for `name:`, `on:`, `permissions:`, `concurrency:`, and
`workflow_call:` in reusable files, plus `actions/upload-artifact@v7` and
`actions/checkout@v7` in the Java workflow.

- [x] **Step 2: Run the contract test and verify the red failure**

Run:

```bash
node --test scripts/workflow-contract.test.mjs
```

Expected: FAIL because the workflow files do not exist yet.

- [x] **Step 3: Add the shared Java setup action and workflows**

Implement the composite action using `actions/setup-java@v5` with Temurin 25
and `gradle/actions/setup-gradle@v4` with read-only cache on pull requests.
Implement `reusable-java-ci.yml` with these jobs:

```yaml
jobs:
  quality:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v7
      - uses: ./.github/actions/setup-java-gradle
      - run: ./gradlew clean check -x integrationTest --no-daemon --no-configuration-cache --stacktrace
      - if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ford-quality-reports-${{ github.run_id }}
          path: |
            ford-ai-service/build/reports/**
            ford-ai-service/build/test-results/**
          if-no-files-found: ignore
          retention-days: 14

  integration:
    needs: quality
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v7
      - uses: ./.github/actions/setup-java-gradle
      - run: ./gradlew integrationTest --no-daemon --no-configuration-cache --stacktrace
      - if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ford-integration-reports-${{ github.run_id }}
          path: |
            ford-ai-service/build/reports/**
            ford-ai-service/build/test-results/**
          if-no-files-found: ignore
          retention-days: 14
```

Add `summary` with `if: always()` and explicit failure when required jobs are
not successful. `ci.yml` invokes the reusable workflow for pull requests,
`main`, and `workflow_dispatch`; pull requests must not publish or deploy.

- [x] **Step 4: Run the contract and syntax checks**

Run:

```bash
node --test scripts/workflow-contract.test.mjs
docker run --rm -v "$PWD:/repo" rhysd/actionlint:1.7.7 -color
```

Expected: the Node contract test passes and actionlint reports no workflow
errors.

- [x] **Step 5: Commit the Java CI foundation**

```bash
git add .github/actions .github/workflows/ci.yml .github/workflows/reusable-java-ci.yml scripts/workflow-contract.test.mjs
git commit -m "ci: add reusable java verification workflows"
```

## Task 2: Add security, container, SBOM, and provenance workflows

**Files:**

- Create: `.github/workflows/reusable-security.yml`
- Create: `.github/workflows/reusable-container.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `scripts/workflow-contract.test.mjs`

**Interfaces:**

- Security workflow uses Trivy vulnerability/secret/misconfiguration scanning and pull-request dependency review without requiring an external NVD credential.
- Container workflow input: `publish` boolean, default `false` for pull requests and `true` on trusted `main`.
- Container workflow output: `image`, `digest`, and `sbom-artifact` job outputs.

- [x] **Step 1: Extend the failing contract test**

Assert that security workflows declare `contents: read`, that container
publishing declares `packages: write` and `id-token: write`, and that the
container workflow contains Trivy SARIF, SBOM, GHCR login, and provenance
attestation steps.

- [x] **Step 2: Run the contract test to verify the expected red failure**

Run `node --test scripts/workflow-contract.test.mjs`.

Expected: FAIL because the security and container workflows are absent.

- [x] **Step 3: Implement the security workflow**

Add separate jobs for Gitleaks and dependency scanning. Upload reports with
`actions/upload-artifact@v7` using 14-day retention. Upload SARIF through
`github/codeql-action/upload-sarif@v4` only for trusted events. Do not expose
NVD, registry, or cloud credentials in command output.

- [x] **Step 4: Implement the container workflow**

Build `ford-ai-service/Dockerfile` with `docker/build-push-action@v6`, tag the
image `ghcr.io/${{ github.repository }}:sha-${{ github.sha }}`, scan it with
`aquasecurity/trivy-action@v0.36.0`, generate an SBOM with
`anchore/sbom-action@v0.20.0`, upload the SBOM and SARIF, and publish only when
the event is `push` to `main` or an explicitly approved manual dispatch.
Use `actions/attest-build-provenance@v2` after the image is pushed and grant
only `packages: write`, `security-events: write`, and `id-token: write` in the
publishing job.

- [x] **Step 5: Run checks and verify reports**

Run:

```bash
node --test scripts/workflow-contract.test.mjs
docker run --rm -v "$PWD:/repo" rhysd/actionlint:1.7.7 -color
docker build -f ford-ai-service/Dockerfile -t ford-service:ci ford-ai-service
```

Expected: contract and actionlint checks pass; the local image builds without
publishing.

- [x] **Step 6: Commit the supply-chain gates**

```bash
git add .github/workflows scripts/workflow-contract.test.mjs
git commit -m "ci: add security and container supply chain gates"
```

## Task 3: Add deterministic AI evaluation reports

**Files:**

- Create: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/AiEvaluationResult.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/JsonlAiEvaluationReportWriter.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/ai/evaluation/AiRegressionEvaluationTest.java`
- Modify: `ford-ai-service/src/test/resources/fixtures/ai/spanish-vehicle-finance-cases.jsonl`
- Modify: `.github/workflows/reusable-java-ci.yml`

**Interfaces:**

```java
record AiEvaluationResult(
    String caseId,
    String applicationVersion,
    String provider,
    String model,
    String intent,
    boolean toolExpectationPassed,
    boolean groundednessPassed,
    boolean financeSafetyPassed,
    boolean leadScorePassed,
    long latencyMs,
    int inputTokens,
    int outputTokens,
    BigDecimal estimatedCostMxn,
    boolean passed,
    String failureReason) {}

interface AiEvaluationReportWriter {
    Path writeJsonLines(Path output, List<AiEvaluationResult> results);
    Path writeSummary(Path output, List<AiEvaluationResult> results);
}
```

- [x] **Step 1: Write the failing lead-handoff routing test**

Add a test for `IntentRouter.route("Quiero comprarla este mes, que me llame un asesor")`
expecting `Intent.LEAD_HANDOFF`. Run `./gradlew test --tests '*IntentRouterTest'`
and confirm compilation/test failure because the enum value and route do not
exist.

- [x] **Step 2: Implement the minimal lead-handoff route**

Add `LEAD_HANDOFF` to `Intent` and recognize `asesor`, `asesora`, `humano`,
`que me llame`, and `quiero comprar` before catalog/inventory matching. Run
the focused test and confirm it passes.

- [x] **Step 3: Write the failing writer and fixture tests**

Test that the writer creates one JSON object per result, preserves UTF-8
Spanish text, writes a Markdown summary, and marks the aggregate run failed
when any deterministic safety or finance assertion fails.

- [x] **Step 4: Run the focused test and verify red**

Run:

```bash
cd ford-ai-service
./gradlew test --tests '*AiRegressionEvaluationTest'
```

Expected: FAIL because the report record and writer do not exist.

- [x] **Step 5: Implement the test-only report writer and deterministic evaluation**

Add stable `case_id` values to the three JSONL fixtures. Execute the fixture
cases using the existing intent router, deterministic finance calculator, and
fake read-only tool ports. Write reports under
`build/reports/ai-evaluation/results.jsonl` and
`build/reports/ai-evaluation/summary.md`. Do not call a live provider.

- [x] **Step 6: Run the focused test and verify green**

Run the focused test again, then inspect both report files. Expected: all
fixture cases pass and the files contain no phone number, email, or raw
provider payload.

- [x] **Step 7: Wire reports into CI**

Add an `evaluation` job to `reusable-java-ci.yml` and upload the two report
files with `actions/upload-artifact@v7`, retention 30 days. Keep live-provider
evaluation behind a protected manual workflow input and environment secret.

- [x] **Step 8: Commit the evaluation artifact feature**

```bash
git add ford-ai-service/src/test .github/workflows/reusable-java-ci.yml
git commit -m "test: publish deterministic ai evaluation reports"
```

## Task 4: Add the self-hosted Langfuse and OpenTelemetry deployment

**Files:**

- Create: `deployment/observability/langfuse/docker-compose.yml`
- Create: `deployment/observability/langfuse/.env.example`
- Create: `deployment/observability/langfuse/.gitignore`
- Create: `deployment/observability/langfuse/README.md`
- Create: `deployment/observability/otel-collector/config.yaml`
- Create: `deployment/observability/otel-collector/README.md`
- Create: `scripts/observability-contract.test.mjs`
- Create: `.github/workflows/reusable-observability.yml`
- Modify: `.gitignore`

**Interfaces:**

- OTLP receiver: `http://otel-collector:4318/v1/traces` inside the Compose network.
- Langfuse Web: port `3000`, reachable from the host only through the configured local/pilot binding.
- Langfuse dependencies: PostgreSQL, ClickHouse, Redis/Valkey, and MinIO with named volumes.
- Collector exporter: Langfuse OTLP endpoint and Basic authentication assembled from environment secrets.

- [x] **Step 1: Write the failing deployment contract test**

Assert that the Compose file contains `langfuse-web`, `langfuse-worker`,
`postgres`, `clickhouse`, `redis`, `minio`, and `otel-collector`; every
stateful service has a named volume; no image uses the `latest` tag; and the
collector configuration contains an OTLP receiver, batch processor, memory
limiter, redaction processor, and Langfuse exporter.

- [x] **Step 2: Run the contract test and verify red**

Run `node --test scripts/observability-contract.test.mjs`.

Expected: FAIL because the deployment files do not exist.

- [x] **Step 3: Add the pinned Compose stack and collector configuration**

Use the current Langfuse v4 Compose topology. Define non-secret settings in
`.env.example`, including `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`,
`LANGFUSE_OTEL_ENDPOINT`, `LANGFUSE_RETENTION_DAYS`, and `OTEL_SERVICE_NAME`.
Do not commit credentials. Set UTC timezone for PostgreSQL and ClickHouse.

Configure the collector with OTLP HTTP input, a memory limiter, batch
processing, an attributes/redaction processor that removes raw prompt,
completion, phone, email, and provider payload fields, and an OTLP HTTP
exporter to Langfuse. Keep exporter retry and queue settings bounded so
telemetry cannot exhaust application resources.

- [x] **Step 4: Add operational documentation and validation workflow**

Document:

```bash
cp deployment/observability/langfuse/.env.example deployment/observability/langfuse/.env
docker compose -f deployment/observability/langfuse/docker-compose.yml up -d
docker compose -f deployment/observability/langfuse/docker-compose.yml ps
docker compose -f deployment/observability/langfuse/docker-compose.yml logs --tail=100 langfuse-web
```

Document startup, health verification, persistent volumes, backup/restore,
retention, upgrades, private-network exposure, and the fact that Compose is
for local/pilot operation rather than HA production.

- [x] **Step 5: Run deployment validation**

Run:

```bash
node --test scripts/observability-contract.test.mjs
docker compose -f deployment/observability/langfuse/docker-compose.yml config --quiet
docker run --rm -v "$PWD/deployment/observability/otel-collector/config.yaml:/etc/otelcol-contrib/config.yaml:ro" otel/opentelemetry-collector-contrib:0.138.0 validate --config=/etc/otelcol-contrib/config.yaml
```

Expected: all contracts pass and Docker reports valid Compose and collector
configuration.

- [x] **Step 6: Commit the observability deployment**

```bash
git add deployment/observability scripts/observability-contract.test.mjs .github/workflows/reusable-observability.yml .gitignore
git commit -m "ops: add self hosted langfuse observability stack"
```

## Task 5: Integrate Spring Boot and Spring AI with OTLP observations

**Files:**

- Modify: `ford-ai-service/gradle/libs.versions.toml`
- Modify: `ford-ai-service/build.gradle.kts`
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/src/test/resources/application-test.yml`
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/AiObservationService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/DefaultAiObservationService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/observability/PiiObservationFilter.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/observability/AiObservationServiceTest.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/observability/PiiObservationFilterTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ai/application/DefaultAssistantOrchestrator.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/outbox/OutboxPublisher.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/ingestion/application/DefaultSyncCoordinator.java`

**Interfaces:**

```java
public interface AiObservationService {
    <T> T observe(String operation, Map<String, String> lowCardinalityTags, Supplier<T> action);
    void event(String operation, Map<String, String> lowCardinalityTags);
}
```

`DefaultAiObservationService` uses the injected `ObservationRegistry`, creates
bounded low-cardinality key values, records errors, and always returns the
business operation’s exception unchanged. It does not put conversation IDs,
tenant IDs, prompt text, or contact data into metric labels.

- [x] **Step 1: Write failing observation and redaction tests**

Test that a successful operation records one observation, a failed operation
records an error and rethrows the original exception, tags outside the
allowlist become `other`, and the redaction filter removes email, phone,
MXN amounts, provider IDs, and raw payload keys.

- [x] **Step 2: Run focused tests and verify red**

Run:

```bash
cd ford-ai-service
./gradlew test --tests '*AiObservationServiceTest' --tests '*PiiObservationFilterTest'
```

Expected: FAIL because the observation service and filter do not exist.

- [x] **Step 3: Add the framework-managed OpenTelemetry dependency**

Add this version-catalog alias and dependency:

```toml
spring-boot-starter-opentelemetry = { module = "org.springframework.boot:spring-boot-starter-opentelemetry" }
```

```kotlin
implementation(libs.spring.boot.starter.opentelemetry)
```

Use Spring Boot’s BOM-managed OTLP tracing support rather than manually
constructing tracer providers. Spring Boot documents this starter as the
supported OpenTelemetry/OTLP path for Spring Boot 4.1.

- [x] **Step 4: Add YAML configuration and implementations**

Add YAML equivalent to:

```yaml
spring:
  ai:
    chat:
      client:
        observations:
          log-prompt: ${FORD_AI_LOG_PROMPT:false}
          log-completion: ${FORD_AI_LOG_COMPLETION:false}

management:
  tracing:
    sampling:
      probability: ${FORD_OTEL_SAMPLING_PROBABILITY:0.1}
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${FORD_OTEL_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
```

Keep test export disabled and prompt/completion logging false. Register the
redaction filter and wrap assistant orchestration, outbox publication, and
Ford synchronization with bounded observations. Preserve Spring AI’s native
ChatClient/model/tool/vector-store observations.

- [x] **Step 5: Run focused and full verification**

Run:

```bash
cd ford-ai-service
./gradlew test --tests '*AiObservationServiceTest' --tests '*PiiObservationFilterTest'
./gradlew clean check --no-configuration-cache
```

Expected: focused tests and the full application verification pass with no
external OTLP endpoint required.

- [x] **Step 6: Commit application telemetry integration**

```bash
git add ford-ai-service/gradle ford-ai-service/build.gradle.kts ford-ai-service/src
git commit -m "feat(observability): add otlp and pii safe ai observations"
```

## Task 6: Add workflow telemetry validation, dashboards, and operations docs

**Files:**

- Create: `deployment/observability/dashboards/ford-service-overview.json`
- Create: `docs/ci-cd.md`
- Create: `docs/observability.md`
- Modify: `ford-ai-service/README.md`
- Modify: `.github/workflows/reusable-observability.yml`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**

- The observability reusable workflow validates Compose and collector files on pull requests.
- The dashboard uses only bounded metrics: request latency/error, model latency/error, tool latency/error, token usage, handoff count, outbox failures, ingestion freshness, and exporter failures.

- [x] **Step 1: Write documentation and dashboard contract tests**

Extend `scripts/observability-contract.test.mjs` to require documentation
sections for startup, health, retention, backups, restore, upgrades, PII,
failure behavior, and incident response. Require dashboard queries to avoid
phone, email, conversation ID, tenant ID, and raw prompt fields.

- [x] **Step 2: Run the contract test and verify red**

Run `node --test scripts/observability-contract.test.mjs`.

Expected: FAIL because the dashboard and operations documents do not exist.

- [x] **Step 3: Add dashboard and operational documentation**

Document PR/main/manual workflow behavior, artifact names and retention,
production environment approval, GHCR image promotion, Langfuse operation,
OTLP endpoint configuration, trace hierarchy, sampling, PII policy, backup
and restore commands, and incident behavior when Langfuse is unavailable.

- [x] **Step 4: Add the validation job and links**

Wire the reusable observability workflow into `ci.yml` after static validation
and before packaging. Link `docs/ci-cd.md` and `docs/observability.md` from
the service README.

- [x] **Step 5: Run documentation and workflow checks**

Run:

```bash
node --test scripts/workflow-contract.test.mjs scripts/observability-contract.test.mjs
docker run --rm -v "$PWD:/repo" rhysd/actionlint:1.7.7 -color
git diff --check
```

Expected: all contract tests and actionlint pass.

- [x] **Step 6: Commit operational documentation**

```bash
git add .github deployment/observability/dashboards docs ford-ai-service/README.md scripts
git commit -m "docs: document ci cd and langfuse operations"
```

## Task 7: Full verification and review handoff

**Files:**

- Modify: `docs/superpowers/plans/2026-09-18-ford-service-ci-observability.md`

- [x] **Step 1: Run the complete local verification**

Run:

```bash
node --test scripts/workflow-contract.test.mjs scripts/observability-contract.test.mjs
docker run --rm -v "$PWD:/repo" rhysd/actionlint:1.7.7 -color
docker compose -f deployment/observability/langfuse/docker-compose.yml config --quiet
cd ford-ai-service
./gradlew clean check --no-configuration-cache
cd ..
git diff --check
```

Expected: all commands exit with status 0. If Docker is unavailable, record
the exact unavailable command and run the remaining checks; do not claim the
deployment validation passed.

- [x] **Step 2: Review the acceptance criteria against evidence**

Confirm that workflow artifacts, SARIF paths, SBOM, attestation conditions,
Langfuse persistence, OTLP redaction, fail-open export behavior, bounded
metrics, and documentation are all represented by a passing test or a
successful command.

- [x] **Step 3: Update the plan status**

Mark completed tasks `✅` and set the plan status to `Complete` only when all
required verification evidence exists. If a deployment target is not
configured, leave target-specific deployment disabled and record that the
image publication and deployment contract are verified independently.

- [x] **Step 4: Commit the plan status and request review**

```bash
git add docs/superpowers/plans/2026-09-18-ford-service-ci-observability.md
git commit -m "docs: record ci observability verification"
```

Request a code review before merging this follow-up work. The original MVP
PR remains separate so automated review is not limited by its 195-file size.

## Dependency Notes

```text
Task 1 -> Task 2 -> Task 6
Task 1 -> Task 3 -> Task 6
Task 4 -> Task 5 -> Task 6
Task 2 + Task 3 + Task 5 + Task 6 -> Task 7
```

Task 4 can be developed independently of the application telemetry code, but
Task 5 consumes its OTLP endpoint and redaction contract. Task 6 should not
enable staging or production deployment until a concrete target supplies its
environment contract and protected secrets.

## Research References

- Spring Boot tracing and OTLP: https://docs.spring.io/spring-boot/reference/actuator/tracing.html
- Spring AI observability: https://docs.spring.io/spring-ai/reference/observability/
- Langfuse self-hosting: https://langfuse.com/self-hosting
- Langfuse Docker Compose deployment: https://langfuse.com/self-hosting/deployment/docker-compose
- Langfuse self-hosted scaling: https://langfuse.com/self-hosting/configuration/scaling
- OpenTelemetry Java/Spring Boot starter: https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/

## Definition of Done

- [x] All tasks are complete and the plan status is `Complete`.
- [x] Workflow contract and observability contract tests pass.
- [x] Actionlint passes for every workflow.
- [x] `ford-ai-service` `clean check` passes.
- [x] Langfuse Compose and collector configuration validate.
- [x] Reports upload on success and failure.
- [x] Image publication is restricted to trusted events.
- [x] SBOM and provenance attestation are produced for published images.
- [x] Spring AI/OTLP tracing is fail-open and PII-safe.
- [x] Operations documentation is complete.
- [x] Code review is requested before merge.
