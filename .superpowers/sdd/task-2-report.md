# Task 2 Report — `ford_catalog_reader` Least-Privilege Verification

Date: 2026-11-02  
Scope: disposable local Testcontainers PostgreSQL only. No live DB, host, credential, role provisioning, Vault, or production changes.

## Contract checked

Extended `ford-ai-service/src/integrationTest/java/com/ford/service/database/SchemaIntegrationTest.java` to verify:

- `ford_catalog_reader` has LOGIN; SUPERUSER, CREATEROLE, CREATEDB, REPLICATION, and BYPASSRLS are false; it has no role memberships.
- SELECT privilege and actual login query (`SELECT * ... LIMIT 0`) work on every explicit reader relation from existing catalog changeset 002: `vehicle_search_v1`, `vehicle_variants_v2`, `vehicle_facts_v2`, `comparison_facts_v2`, `offers_v2`, `catalog_documents_v1`, `catalog_pages_v1`, `catalog_status_v1`, `promotions_v3`.
- INSERT, UPDATE, DELETE, and TRUNCATE privileges are absent on each approved relation.
- SELECT remains denied for `retrieval_document_manifest`, `reviewed_fact_manifest`, `price_import_journal`, `document_provenance_v1`, unapproved base tables `vehicle_images_v1`, `vehicle_facts_v1`, `page_links_v1`, and `ford_metadata`; existing login-level permission-denial probes remain.

No grant gap was demonstrated. Changeset 002 was not edited; no additive changeset or invented schema/identity was added. The known retrieval SQL `ford_read` mismatch remains unresolved and is still a separate gate; callbacks must remain disabled until adapters/migrations agree.

## TDD and verification

Assertions were added before running tests. First integration run exposed an assertion-order mistake in the new expected role flags (test expected LOGIN false). Actual disposable role flags were `[true, false, false, false, false, false]`, matching the intended contract. Corrected the test expectation; no production/migration change was needed.

Final command sequence, run from `ford-ai-service`:

```text
./gradlew test
```

Result: `BUILD SUCCESSFUL` (5 actionable tasks, 5 up-to-date). Gradle emitted its existing Gradle 10 deprecation warning.

```text
DOCKER_HOST="unix://${HOME}/.colima/native/docker.sock" ./gradlew integrationTest --tests com.ford.service.database.SchemaIntegrationTest
```

Result: `BUILD SUCCESSFUL`; focused `SchemaIntegrationTest` passed. The successful execution was run after correcting the expected role flags. A subsequent ordered verification reported both tasks `UP-TO-DATE`; the earlier successful integration execution ran the compiled assertions against disposable Testcontainers PostgreSQL.

## Container safety / cleanup

- Started only `colima native`; Docker target was `unix://${HOME}/.colima/native/docker.sock`.
- After the passing integration run, an immediate `docker ps -a` showed the Testcontainers Ryuk container briefly still running. A subsequent check showed no containers:

```text
DOCKER_HOST="unix://${HOME}/.colima/native/docker.sock" docker ps -a --format '{{.ID}} {{.Names}} {{.Image}} {{.Status}}'
```

Output was empty. Stopped `colima native` only after that empty listing. Did not use `colima-testbox`, `colima-emme`, or other Docker endpoints.

## Files and commits

- `ford-ai-service/src/integrationTest/java/com/ford/service/database/SchemaIntegrationTest.java` — added reader privilege contract assertions.
- `.superpowers/sdd/task-2-report.md` — this report.
- Test commit: `c4041c2` (`test: enforce catalog reader privilege boundaries`).
- Report artifact introduced in commit: `d885477` (`docs: report catalog reader verification`).
- Existing unrelated untracked planning documents were left untouched.
