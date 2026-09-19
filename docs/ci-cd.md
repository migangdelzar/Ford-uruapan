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

```bash
node --test scripts/workflow-contract.test.mjs \
  scripts/observability-contract.test.mjs \
  scripts/operations-contract.test.mjs
docker run --rm -w /repo -v "$PWD:/repo" rhysd/actionlint:1.7.7 -color
cd ford-ai-service
./gradlew clean check --no-configuration-cache
```

For the persisted Colima `emme` profile, the Gradle test tasks set
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` so Testcontainers
Ryuk binds the Docker socket inside the VM. CI uses a standard GitHub-hosted
Docker service and does not need that override. If a shell predates the global
profile configuration, reload it with `source ~/.zshrc` or run
`ford-colima-recover` before the integration suite.

The verification suite includes Spring Modulith architecture/documentation
checks, PostgreSQL event-publication and outbox recovery tests, signed
Meta-to-outbox replay coverage, canonical ingestion upsert coverage, and PII
observation-filter tests. Keep the MVC + virtual-thread load gate in
`ford-ai-service/docs/performance-baseline.md` current when workload evidence
is refreshed.
