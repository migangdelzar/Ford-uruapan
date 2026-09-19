# Ford Service CI/CD and Observability Design

| Field | Value |
|---|---|
| Date | 2026-09-18 |
| Status | Approved direction in conversation; implementation pending |
| Repository | `Ford-uruapan` |
| Application | `ford-ai-service` |
| Scope | Repository-local enterprise pipeline and self-hosted Langfuse observability |

## 1. Purpose

Add a production-oriented delivery and observability foundation without
splitting the current project into Git submodules or separate repositories.
The application, GitHub Actions workflows, OpenTelemetry configuration, and
self-hosted Langfuse deployment remain versioned together. Each operational
component remains independently deployable within the repository.

The design must preserve the existing MVP choices: Spring MVC with virtual
threads, Spring AI, PostgreSQL/pgvector, Redis, JDBC, YAML configuration,
Spanish/MXN behavior, and explicit application ports.

## 2. Repository architecture

```text
Ford-uruapan/
  .github/
    actions/
      setup-java-gradle/
    workflows/
      ci.yml
      reusable-java-ci.yml
      reusable-security.yml
      reusable-container.yml
      reusable-observability.yml
      reusable-deploy.yml
  deployment/
    observability/
      langfuse/
        docker-compose.yml
        .env.example
        README.md
      otel-collector/
        config.yaml
        README.md
      dashboards/
  docs/
    ci-cd.md
    observability.md
  ford-ai-service/
```

No Git submodule is required. The workflow and deployment directories are
repository-local, while their contracts are designed so they can later move
to shared platform repositories without changing the application modules.
External Langfuse and collector images are pinned to explicit release
versions; the Langfuse source repository is not vendored.

## 3. GitHub Actions design

`ci.yml` is the entrypoint for pull requests, pushes to `main`, and manual
dispatch. It calls local reusable workflows with `workflow_call`. Reusable
workflows own job-level permissions, timeouts, concurrency, artifact naming,
and result publication. The entrypoint owns event filters and promotion order.

### Pull request pipeline

```text
validate
  -> unit-and-architecture
  -> integration
  -> security
  -> container-contract
  -> result-summary
```

Required PR gates:

- YAML/workflow syntax and action reference validation.
- Java 25 and Gradle wrapper validation.
- Unit tests and architecture tests.
- PostgreSQL/pgvector/Redis integration tests with Testcontainers.
- Dependency review, secret scan, dependency vulnerability scan, and image
  build validation.
- Upload JUnit XML, HTML reports, coverage, scan reports, and AI fixture
  evaluation results as artifacts even when an earlier test fails.
- Upload SARIF findings to GitHub Code Scanning when a scanner emits SARIF.

PR workflows do not publish images, deploy environments, or export test
prompts/completions to Langfuse.

### Main pipeline

After all PR gates pass on `main`:

1. Build the boot JAR.
2. Build a container tagged with the commit SHA.
3. Generate an SBOM.
4. Scan the image and dependencies.
5. Publish the immutable image to GHCR.
6. Generate build provenance and an artifact attestation.
7. Run staging deployment only when the configured deployment environment is
   enabled.
8. Run health, API, webhook-contract, and AI-regression smoke tests.
9. Require a protected environment approval before production promotion.
10. Deploy the same immutable image digest and run post-deployment smoke tests.

The first implementation must fail closed when a deployment environment is
enabled but its required target contract is incomplete. It may publish the
image without deploying when no target has been configured.

### Result retention

| Result | Storage | Initial retention |
|---|---|---:|
| JUnit/XML and HTML reports | GitHub Actions artifact | 14 days |
| Coverage reports | GitHub Actions artifact | 14 days |
| AI regression JSONL and summary | GitHub Actions artifact | 30 days |
| Load-test results | GitHub Actions artifact | 30 days |
| SARIF findings | GitHub Code Scanning | GitHub policy |
| Container image and SBOM | GHCR/package metadata | release policy |
| Provenance attestation | GitHub artifact attestations | repository policy |

Workflow logs must not print secrets, raw payloads, prompts, completions, or
customer contact data. Job summaries contain counts, links, durations, and
failure categories only.

## 4. Self-hosted observability architecture

```text
Ford service
  -> Spring AI observations + Micrometer observations
  -> OTLP over HTTP/protobuf
  -> OpenTelemetry Collector
       -> redaction and sampling
       -> Langfuse OTLP endpoint for AI traces
       -> application metrics/logging backend when configured
```

The application does not require Langfuse to answer a customer. Telemetry
export is asynchronous and fail-open: exporter or Langfuse outages must not
block webhook acknowledgement, deterministic finance calculations, or human
handoff creation.

### Langfuse deployment

The local/pilot deployment is a separate Compose project under
`deployment/observability/langfuse`. It includes the Langfuse Web and Worker,
PostgreSQL, ClickHouse, Redis/Valkey, and S3-compatible blob storage. Every
stateful service uses a named persistent volume. Secrets are supplied through
an ignored environment file or deployment secret manager; committed files
contain no secrets.

The production contract requires:

- private network access for Worker, PostgreSQL, ClickHouse, Redis, and blob
  storage;
- TLS and authentication at the Web/API boundary;
- backups for PostgreSQL, ClickHouse, and blob storage;
- restore verification;
- disk, CPU, memory, queue, and ingestion-latency alerts;
- explicit trace retention and deletion policy;
- pinned image versions and documented upgrade procedure.

Docker Compose is the local and pilot path. Kubernetes/Helm is a later
deployment target for high availability. The application integration remains
unchanged between both targets.

### Trace model

One inbound customer interaction has a parent trace with child spans for:

- Meta webhook receipt and signature validation;
- message normalization and persistence;
- intent routing;
- Spring AI ChatClient/model invocation;
- catalog, finance, and knowledge tool calls;
- vector retrieval and source selection;
- lead scoring;
- handoff creation;
- outbox enqueue and channel delivery.

Low-cardinality metrics use bounded values only: channel, provider, model
family, tool name, outcome, and lead band. Conversation IDs and tenant-safe
hashes are trace attributes, never metric labels.

## 5. Privacy and security rules

- Spring AI prompt/completion capture is disabled by default in production.
- A PII redactor runs before telemetry leaves the application or collector.
- Phone numbers, names, email addresses, government IDs, bank data, exact
  income, and raw Meta payloads are never exported by default.
- Development and staging can opt into controlled redacted payload capture.
- AI traces include provider, model version, latency, token counts, cost
  estimate, tool names, retrieval source IDs, and outcome.
- Finance calculations remain deterministic and auditable; Langfuse records
  the trace evidence but does not become the financial source of truth.
- Production workflow permissions are least-privilege and deployment
  credentials use OIDC where the target supports it.
- Production promotion uses GitHub environment protection and approval.

## 6. AI evaluation and stored results

The repository fixture set is executed in CI with deterministic provider
stubs. A manual or protected staging workflow may run live-provider tests.
Live-provider credentials are never available to untrusted pull requests.

Each evaluation record contains:

```text
case_id
application_version
provider
model
intent
tool_expectations
groundedness_result
finance_result
lead_score_result
latency_ms
input_tokens
output_tokens
estimated_cost_mxn
pass
failure_reason
```

The workflow stores JSONL results and a Markdown summary as artifacts. CI
fails when deterministic safety, tool-selection, finance, or lead-score gates
fail. Qualitative LLM-as-judge scores are reported first and become blocking
only after a reviewed baseline exists.

## 7. Testing and acceptance criteria

Implementation is accepted when:

- PR workflows run with no long-lived cloud credentials.
- All required reports are uploaded on both success and failure.
- The container is published only from trusted `main` or an approved manual
  workflow.
- The published image has an SBOM and provenance attestation.
- The Langfuse Compose project validates and starts with persistent storage.
- The collector configuration validates and forwards a redacted test trace.
- A Ford service test proves telemetry export failure does not fail the chat
  response path.
- A Spring AI test proves model, tool, retrieval, and handoff observations
  share the inbound trace context.
- No production test or workflow output contains unredacted customer data.
- The same immutable image digest is used for staging and production.
- Operations documentation describes startup, health checks, backup, restore,
  retention, upgrades, and incident response.

## 8. Explicit non-goals

- No Git submodules.
- No separate microservice split for catalog, finance, channels, or leads.
- No automatic production deployment without environment approval.
- No raw prompt/completion retention in production by default.
- No LangGraph4j adoption solely for observability.
- No requirement for a specific cloud provider before the deployment target
  is selected.

## 9. Implementation sequence

1. Add repository-local reusable workflow contracts and PR CI.
2. Add security, SBOM, artifact, and report publication gates.
3. Add self-hosted Langfuse and OpenTelemetry Compose configuration.
4. Add Spring Boot/Spring AI OTLP integration and redaction enforcement.
5. Add AI evaluation result artifacts and staging smoke workflow.
6. Add target-specific deployment only after the staging environment contract
   is supplied.

This sequence keeps each change independently reviewable and prevents the
existing oversized MVP PR from absorbing unrelated operational infrastructure.
