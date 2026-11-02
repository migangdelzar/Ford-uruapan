# Task 6 Report — Deployment path and guarded empty reset

Date: 2026-11-02

## Changes

- Added emme-2-only empty-reset and rollback playbooks. They require explicit approval inputs, verify an exact `ford_service` backup manifest and both archive hashes, check configured paths/canonical paths/symlinks, stop only the named `data` Compose project's PostgreSQL and Redis services, and move exact bind directories without deleting old or fresh data.
- Reset writes a mode-0600 exact-path state record before stopping services. Rollback validates that record, target, approval, digest, and path names; it preserves new directories and restores only recorded old directories. Both paths reject missing evidence and archive collisions.
- Added executable production Mise wrappers requiring production profile, reset approval ID, backup manifest SHA, exact `--limit data`, and no caller-supplied Ansible arguments.
- `deploy:data` includes tagged private Ollama after data services. `deploy:all` orders data before Service and never runs reset, scraper/scheduler, catalog sync, MCP, or tunnel deployment.
- Production Service deploy now rejects mutable image tags before loading deployment configuration. PostgreSQL and Redis use configured immutable digest references; `deploy:data` validates digest syntax and fails closed when either is missing. Owner-reviewed digest values remain intentionally blank in the example config; no digest was invented.
- Added operations and Mise contracts for target/approval boundaries, immutable image requirements, and writer/MCP exclusion.

## TDD and verification

- RED — focused deployment contract failed because the reset playbook did not exist.
- RED — immutable-image contract failed against mutable PostgreSQL/Redis tags.
- RED — production Service tag rejection test failed on missing-config error before image validation; validation moved ahead of config loading for explicit image inputs.
- `node --test scripts/operations-contract.test.mjs scripts/workflow-contract.test.mjs scripts/mise-contract.test.mjs` — **92 passed, 0 failed**.
- `(cd ford-ai-service && ./gradlew test)` — **BUILD SUCCESSFUL**.
- `(cd ford-ai-service && DOCKER_HOST="unix://${HOME}/.colima/native/docker.sock" ./gradlew integrationTest --tests com.ford.service.database.SchemaIntegrationTest --tests com.ford.service.database.FordEmptyDatabaseBootstrapIntegrationTest)` — **BUILD SUCCESSFUL** on isolated `colima-native`.
- `(cd ford-ai-service && ./gradlew bootJar)` — **BUILD SUCCESSFUL**.
- `ansible-playbook --syntax-check -i localhost, -c local deployment/ansible/ford-empty-reset.yml` — passed.
- `ansible-playbook --syntax-check -i localhost, -c local deployment/ansible/ford-empty-rollback.yml` — passed.
- `ansible-playbook --syntax-check -i localhost, -c local deployment/ansible/site.yml` — passed.
- `bash -n` for changed Mise scripts and `git diff --check` — passed.
- Reset and rollback wrappers with missing approvals both exited 2 before config loading or host access.
- `colima-native` had no containers after integrations and was stopped. No playbook execution, host access, backup operation, image pull, or live change occurred.

## Safety boundary

- Local code and disposable PostgreSQL checks pass. The reset/rollback playbooks were syntax-checked only; they were not run against real or simulated data directories.
- Production deployment remains unauthorized. Actual DB role/schema contract, runtime secret/capacity evidence, target-specific backup proof, and protected release approvals remain pending.
- Immutable PostgreSQL/Redis and Service image digests must come from reviewed release artifacts before a production deployment can pass preflight.

## Commit

- Implementation commit: `cea643d` (`feat(deployment): guard empty Ford replacement path`).
