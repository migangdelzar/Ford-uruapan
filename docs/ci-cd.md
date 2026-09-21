# CI/CD

Ford Service keeps the pipeline in `.github/workflows/` and composes the main
workflow from reusable workflows. Pull requests and pushes to `main` run Java
quality checks, deterministic AI evaluation, integration tests, security
gates, observability configuration validation, and container gates.

## Evidence

The Java, security, and container jobs upload reports with the GitHub Actions
artifact service. Reports include Gradle test results, AI evaluation JSONL and
summary files, Trivy SARIF, and the CycloneDX SBOM. Artifacts are retained for
14 days, except AI evaluation reports, which are retained for 30 days.

The container job builds the immutable `sha-<commit>` image, scans it, and
generates an SBOM on pull requests. It publishes to GHCR only for a push to
`main` or a manually approved dispatch with `publish_container=true`. Published images receive a
GitHub build-provenance attestation. Pull requests never publish images or
upload SARIF to the repository security tab.

## Local checks

The preferred local entry point is mise from `ford-ai-service`:

```bash
cd ford-ai-service
mise run test:check
mise run test:ai-evaluation
mise run docker:config
mise run observability:config
```

Fresh local hosts should start with the platform-aware setup flow:

```bash
mise run setup:check
mise run setup:host
mise run setup:host -- --apply
mise run setup:docker -- --apply
mise run setup:env
```

Fedora and Oracle Linux use rootless Docker Engine through `dnf`; no Docker
group membership is required. macOS uses the `emme` Colima profile. The Docker
daemon is root inside Colima’s isolated VM, and the runtime helper derives its
profile socket for Compose and Testcontainers. CI runners use their standard
`/var/run/docker.sock` and pass that path explicitly to the integration job.

The equivalent direct commands remain useful for matching CI exactly:

```bash
node --test scripts/workflow-contract.test.mjs \
  scripts/observability-contract.test.mjs \
  scripts/operations-contract.test.mjs
docker run --rm -w /repo -v "$PWD:/repo" rhysd/actionlint:1.7.7 -color
cd ford-ai-service
./gradlew clean check --no-configuration-cache
```

For the persisted Colima `emme` profile, the runtime helper sets the
Testcontainers in-VM socket override. On Linux it derives the rootless user
socket. If a shell predates the global profile configuration, reload it with
`source ~/.zshrc` or run `ford-colima-recover` before the integration suite.

The verification suite includes Spring Modulith architecture/documentation
checks, PostgreSQL event-publication and outbox recovery tests, signed
Meta-to-outbox replay coverage, canonical ingestion upsert coverage, and PII
observation-filter tests. Keep the MVC + virtual-thread load gate in
`ford-ai-service/docs/performance-baseline.md` current when workload evidence
is refreshed.
