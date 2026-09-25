# Meta Webhook Ansible Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Meta webhook configuration propagation through Mise and Ansible, fix local ARM64 image transfer, and verify the deployed webhook flow.

**Architecture:** The ignored operator environment remains the source of truth. Mise validates and renders private Ansible inventory variables; Ansible templates the app environment and starts the existing Compose project. The application startup initializer reconciles the WhatsApp binding in PostgreSQL.

**Tech Stack:** Bash, Mise, Ansible, Docker Compose, Spring Boot, Java, Gradle, PostgreSQL.

## Global Constraints

- Do not print or commit Meta secrets, provider tokens, database passwords, or private payloads.
- Keep app deployment limited to `emme-1`; do not reset, delete, or recreate data volumes.
- Preserve unrelated existing changes in `ApiExceptionHandler.java` and its test.
- Use the existing application environment names `FORD_META_APP_SECRET`, `FORD_META_VERIFY_TOKEN`, `FORD_META_AUTO_BINDING_ENABLED`, `FORD_META_PROVIDER_ACCOUNT_ID`, and `FORD_TENANT_ID`.

---

### Task 1: Harden webhook signature validation

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/channels/security/MetaSignatureVerifier.java`
- Test: `ford-ai-service/src/test/java/com/ford/service/channels/MetaWebhookSecurityTest.java`

- [x] Add a test asserting a blank app secret raises `SecurityException`.
- [x] Run the focused test and confirm it fails because the verifier currently wraps the empty-key failure as `IllegalStateException`.
- [x] Reject blank secrets before constructing the HMAC key.
- [x] Run the focused test and confirm valid signatures still pass and blank secrets raise `SecurityException`.

### Task 2: Propagate complete Meta configuration through Ansible

**Files:**
- Modify: `deployment/config/oci.env.example`
- Modify: `ford-ai-service/.mise/lib/remote.sh`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Test: `scripts/operations-contract.test.mjs`

- [x] Add the five Meta deployment variables to the example and assert them in the operations contract.
- [x] Extend `require_deploy_config` to require app secret and verify token, and require numeric provider account plus UUID tenant when automatic binding is enabled.
- [x] Render provider account, tenant UUID, and binding switch into the private inventory.
- [x] Render those values into the remote mode-0600 app environment.
- [x] Assert the contract includes the complete source-to-template path and does not expose credential-shaped values in tracked templates.

### Task 3: Transfer locally built ARM64 images and add deployment smoke checks

**Files:**
- Modify: `ford-ai-service/.mise/tasks/deploy/app`
- Modify: `deployment/ansible/site.yml`
- Test: `scripts/operations-contract.test.mjs`

- [x] Add contract assertions that local builds call `docker save`, pass `app_image_archive`, and only use `app_image_preloaded=true` for the remote-build branch.
- [x] Save local images under the private deployment state directory and pass the archive to the existing Ansible copy/load tasks.
- [x] Add a non-secret app verification task that checks the configured webhook verification endpoint shape after health succeeds.
- [x] Run shell syntax and contract tests.

### Task 4: Build and propagate to EMME

- [x] Run focused Gradle and contract tests.
- [ ] Confirm the ignored operator config contains non-empty real Meta values using lengths only; currently blocked because both values are empty.
- [x] Save the current remote app image to the private local deployment state directory for rollback.
- [ ] Run `mise run deploy:check` and `mise run deploy:app`; preflight safely stops at the missing Meta values.
- [ ] Verify remote app environment key presence/lengths, application health, webhook verification, signed POST acceptance, and one enabled WhatsApp binding after credentials are supplied.
