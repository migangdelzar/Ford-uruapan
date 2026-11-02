# Ford Catalog Readiness Prep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve local catalog-release readiness with consistent documentation and regression tests for the observed dual-schema/dual-ledger database baseline, without writing to production or shared test databases.

**Architecture:** Keep production and shared environments read-only. Characterize the migration/bootstrap fail-closed behavior with disposable Testcontainers fixtures, reconcile docs to the approved `public`-present-empty contract, and record external gates without changing approved source bytes, hashes, schemas, or feature flags.

**Tech Stack:** Java 25, Gradle, JUnit 5, AssertJ, Testcontainers, PostgreSQL 16, Liquibase YAML, Markdown, Python 3.

## Global Constraints

- No writes to `emme-1`, `emme-2`, production/shared-test databases, object storage, Vault, DNS, Cloudflare, or deployment configuration.
- Do not run Liquibase or snapshot loads against a live/shared database; use disposable Testcontainers only when Docker is already available.
- Built-in `public` remains present and empty; never drop it or use `CASCADE`.
- Preserve approved archive SHA-256 `a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d` and all manifest hashes.
- Do not choose `ford` or `public` as authoritative if duplicated rows differ; compare on a verified restore and stop for owner direction.
- Keep `FORD_CATALOG_TOOLS_ENABLED=false`; do not push, deploy, or enable tools.
- Preserve unrelated dirty workspace changes; stage only files listed by the task being committed.

---

## File Map

- Modify `docs/superpowers/specs/2026-11-01-ford-single-database-liquibase-catalog-design.md` to keep `public` present and empty rather than remove it.
- Modify `docs/superpowers/plans/2026-11-01-ford-database-cutover-release.md` and `docs/superpowers/plans/2026-11-01-ford-liquibase-schema-and-data.md` to remove conflicting `public`-removal instructions while preserving their other content.
- Modify `docs/operations/production-rollout-readiness.md` to distinguish workspace checks from external blockers, including Docker/source-owner/DBA/backup limits.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordMetadataLiquibaseBootstrapIntegrationTest.java` to represent simultaneous `ford` and `public` Liquibase histories.
- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordMetadataSchemaCutoverIntegrationTest.java` to represent duplicate operational tables in `ford` and `public` and assert fail-closed behavior without row movement.
- Do not modify committed changesets 025/026 in this readiness-only plan. Their production-baseline remediation needs a verified restore and explicit row ownership evidence.

### Task 1: Align cutover documentation with schema contract

**Files:**
- Modify: `docs/superpowers/specs/2026-11-01-ford-single-database-liquibase-catalog-design.md`
- Modify: `docs/superpowers/plans/2026-11-01-ford-database-cutover-release.md`
- Modify: `docs/superpowers/plans/2026-11-01-ford-liquibase-schema-and-data.md`
- Modify: `docs/operations/production-rollout-readiness.md`

- [ ] Update the design spec's references at the opening decisions, migration design, stop conditions, and acceptance section so they say `public` stays present and empty; preserve the rule against `CASCADE`.
- [ ] Update the cutover release plan's line-14 global constraint, line-51 migration step, and lines 56/75 verification criteria so they no longer instruct dropping `public` or expect it absent. Add a top-level note that production writes require the verified-restore gates in `docs/operations/production-rollout-readiness.md`.
- [ ] Add this note below the cutover release plan's title so older steps cannot be mistaken for live authorization:

```markdown
> **Readiness gate:** This plan is subordinate to `docs/operations/production-rollout-readiness.md`. Do not perform a production write until the full backup/restore, source-integrity, migration-rehearsal, and protected-release gates pass.
```
- [ ] Update the Liquibase data plan's line-15 public-schema constraint to require present-but-empty, not schema removal.
- [ ] Add a concise readiness update stating that this plan is workspace-only, the approved manifest hashes match exporter constants but the reported source export mismatch was not reproduced, Docker is unavailable, and source-owner/DBA/restore gates remain open. Do not change hashes or mark these gates passed.
- [ ] Search the touched docs for conflicting target language:

```bash
rg -n -i 'drop( the)? `?public|public (is )?absent|public must be removed|remove(d)? `?public' \
  docs/superpowers/specs/2026-11-01-ford-single-database-liquibase-catalog-design.md \
  docs/superpowers/plans/2026-11-01-ford-database-cutover-release.md \
  docs/superpowers/plans/2026-11-01-ford-liquibase-schema-and-data.md
```

Expected: inspect each match; positive instructions to remove/drop `public` or expect it absent are eliminated. Negated safety rules such as "never drop `public`" are valid matches.

- [ ] Do not commit the four documentation files in this task as whole files: they already contain prior uncommitted design/readiness work. Leave them uncommitted unless exact task-only hunks can be staged and `git diff --cached` proves no prior changes are included. Never commit unrelated pre-existing work to satisfy this plan.

```bash
git diff -- docs/superpowers/specs/2026-11-01-ford-single-database-liquibase-catalog-design.md \
  docs/superpowers/plans/2026-11-01-ford-database-cutover-release.md \
  docs/superpowers/plans/2026-11-01-ford-liquibase-schema-and-data.md \
  docs/operations/production-rollout-readiness.md
```

### Task 2: Characterize dual Liquibase history refusal

**Files:**
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordMetadataLiquibaseBootstrapIntegrationTest.java`

- [ ] Extend `setUp()` in `FordMetadataLiquibaseBootstrapIntegrationTest` to remove only any stale named `ford.databasechangelog` and `ford.databasechangeloglock` tables and then `drop schema if exists ford` without `CASCADE`.
- [ ] Replace `refusesToMergeTrackingTablesFoundInDifferentSchemas()` with the following production-shaped fixture. Include the required imports only if absent:

```java
@Test
void refusesToMergeHistoriesSplitBetweenFordAndPublicWithoutMovingRows() {
    jdbc.sql("create schema ford").update();
    jdbc.sql("create table public.databasechangelog (id text, author text, filename text, md5sum text)").update();
    jdbc.sql("create table public.databasechangeloglock (id integer primary key, locked boolean, lockgranted timestamp, lockedby text)").update();
    jdbc.sql("create table ford.databasechangelog (id text, author text, filename text, md5sum text)").update();
    jdbc.sql("create table ford.databasechangeloglock (id integer primary key, locked boolean, lockgranted timestamp, lockedby text)").update();
    jdbc.sql("insert into public.databasechangelog values ('public-001', 'test', 'public.yaml', '8:public')").update();
    jdbc.sql("insert into public.databasechangeloglock values (1, false, null, 'public-lock')").update();
    jdbc.sql("insert into ford.databasechangelog values ('ford-001', 'test', 'ford.yaml', '8:ford')").update();
    jdbc.sql("insert into ford.databasechangeloglock values (1, false, null, 'ford-lock')").update();

    assertThatThrownBy(bootstrap::initialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ambiguous");

    assertThat(tableExists("public", "databasechangelog")).isTrue();
    assertThat(tableExists("public", "databasechangeloglock")).isTrue();
    assertThat(tableExists("ford", "databasechangelog")).isTrue();
    assertThat(tableExists("ford", "databasechangeloglock")).isTrue();
    assertThat(jdbc.sql("select md5sum from public.databasechangelog where id = 'public-001'")
            .query(String.class).single()).isEqualTo("8:public");
    assertThat(jdbc.sql("select md5sum from ford.databasechangelog where id = 'ford-001'")
            .query(String.class).single()).isEqualTo("8:ford");
    assertThat(jdbc.sql("select lockedby from public.databasechangeloglock where id = 1")
            .query(String.class).single()).isEqualTo("public-lock");
    assertThat(jdbc.sql("select lockedby from ford.databasechangeloglock where id = 1")
            .query(String.class).single()).isEqualTo("ford-lock");
}
```

- [ ] Run the focused test before and after the fixture edit:

```bash
cd ford-ai-service
./gradlew integrationTest --tests '*FordMetadataLiquibaseBootstrapIntegrationTest'
```

Expected: PASS when the local Testcontainers PostgreSQL starts. If Docker is unavailable, report the infrastructure block; do not start/recreate a Colima disk or substitute a shared database.

- [ ] Commit only `FordMetadataLiquibaseBootstrapIntegrationTest.java` after the focused test is verified:

```bash
git add ford-ai-service/src/integrationTest/java/com/ford/service/database/FordMetadataLiquibaseBootstrapIntegrationTest.java
git commit -m "test: cover split Ford Liquibase histories"
```

### Task 3: Characterize duplicate application relation refusal

**Files:**
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/database/FordMetadataSchemaCutoverIntegrationTest.java`

- [ ] Add this test method to `FordMetadataSchemaCutoverIntegrationTest`; include `ford.ai_execution` cleanup in `seedLegacySchemas()` before dropping `ford` without `CASCADE`:

```java
@Test
void refusesDuplicateOperationalTableInFordAndPublicWithoutMovingRows() {
    jdbc.sql("create schema ford").update();
    jdbc.sql("create table ford.ai_execution (id integer primary key, marker text not null)").update();
    jdbc.sql("create table public.ai_execution (id integer primary key, marker text not null)").update();
    jdbc.sql("insert into ford.ai_execution values (1, 'ford-copy')").update();
    jdbc.sql("insert into public.ai_execution values (1, 'public-copy')").update();

    assertThatThrownBy(this::applyCutover)
            .hasStackTraceContaining("Relation ai_execution exists in an unreviewed schema");

    assertThat(tableExists("ford", "ai_execution")).isTrue();
    assertThat(tableExists("public", "ai_execution")).isTrue();
    assertThat(tableExists("ford_metadata", "ai_execution")).isFalse();
    assertThat(jdbc.sql("select marker from ford.ai_execution where id = 1")
            .query(String.class).single()).isEqualTo("ford-copy");
    assertThat(jdbc.sql("select marker from public.ai_execution where id = 1")
            .query(String.class).single()).isEqualTo("public-copy");
    assertThat(schemaExists("public")).isTrue();
}
```

- [ ] In `seedLegacySchemas()`, first execute `drop table if exists ford.ai_execution`, then `drop schema if exists ford` with no cascade, before seeding its existing `ford_metadata`, `ford_graph`, and `public` fixtures.
- [ ] Run the focused integration test:

```bash
cd ford-ai-service
./gradlew integrationTest --tests '*FordMetadataSchemaCutoverIntegrationTest'
```

Expected: PASS when local Testcontainers PostgreSQL is available. A failure before the test body due to unavailable Docker is a blocked verification, not a code pass.

- [ ] Commit only `FordMetadataSchemaCutoverIntegrationTest.java` after the focused test is verified:

```bash
git add ford-ai-service/src/integrationTest/java/com/ford/service/database/FordMetadataSchemaCutoverIntegrationTest.java
git commit -m "test: cover ambiguous Ford schema cutover"
```

### Task 4: Run local verification and report remaining gates

**Files:**
- Read: all files changed in Tasks 1–3
- Modify: `docs/operations/production-rollout-readiness.md` only if fresh local results change the evidence

- [ ] Run fast unit tests:

```bash
cd ford-ai-service
./gradlew test
```

Expected: zero failed tests.

- [ ] Run catalog exporter unit tests:

```bash
cd ford-ai-service
python3 -m unittest scripts.tests.test_export_liquibase_snapshot -v
```

Expected: zero failed tests. This does not prove the approved source database matches the manifest.

- [ ] Run both focused integration commands from Tasks 2 and 3 only if Docker is already available. Do not touch production/shared-test databases or change Colima state to make them run.
- [ ] Run `git diff --check` and inspect the exact diff. Confirm no archive/hash, changeset, deployment configuration, feature-flag, unrelated dirty file, or external-state change was introduced.
- [ ] Leave source integrity, full production inventory, row-authority decision, backup/restore, least-privilege role, migration rehearsal, protected approvals, immutable artifact, deploy, and enablement gates marked open until fresh external evidence is supplied.

**Verification:** Local-only checks are freshly recorded; unavailable integration/restore gates remain blocked; production/shared environments remain unchanged.
