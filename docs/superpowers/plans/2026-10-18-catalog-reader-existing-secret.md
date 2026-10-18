# Catalog Reader With Existing Database Secret — Implementation Plan

> **For agentic workers:** Execute this plan inline across the Service and OCI-action worktrees. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Spring AI catalog tools through a separate PostgreSQL reader login while reusing the existing `FORD_DATABASE_PASSWORD` Vault value and adding no new secret.

**Architecture:** Ford-scraping remains the schema/migration/catalog writer. Its backup-gated scheduler release applies forward-only views, then creates or repairs `ford_catalog_reader` with SELECT-only grants before replacing the scheduler. Ford Service keeps its separate catalog `JdbcClient` and datasource, but resolves the catalog password from the existing `FORD_DATABASE_PASSWORD` property; production callbacks remain disabled until read-only access is verified.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring JDBC, PostgreSQL 16, Ansible 2.21.4, GitHub Actions, mise.

## Global Constraints

- No new Vault secret, OCID, GitHub secret, or Terraform secret-name grant.
- PostgreSQL login name is `ford_catalog_reader`; its password equals the existing `FORD_DATABASE_PASSWORD` value.
- Grant only `CONNECT` on `ford_service`, `USAGE` on `ford_read`, and `SELECT` on current/future `ford_read` views.
- Keep Service catalog callbacks disabled until protected role verification succeeds.
- Never give the catalog datasource the `ford` writer username.
- Preserve the old scheduler until the backup, migration, reader setup, schedule, and health checks pass.
- Keep MCP runtime/routes until the Service catalog smoke and rollback window pass.

---

### Task 1: Reuse the existing database password in Spring's catalog datasource

**Files:**
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Test: `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogDataSourceConfigurationTest.java`
- Modify: `.github/actions/oci-blue-green/action.yml`
- Modify: `.github/actions/oci-blue-green/deploy.sh`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `.github/workflows/release.yml`
- Test: `tests/test_action_package_contract.py`
- Test: `ford-ai-service/scripts/tests/test_oci_action_contract.py`
- Test: `scripts/workflow-contract.test.mjs`

**Interfaces:**
- Consumes: Existing `FORD_VAULT_SECRET_OCID_FORD_DATABASE_PASSWORD` → `FORD_DATABASE_PASSWORD` mapping from `OciVaultEnvironmentPostProcessor`.
- Produces: `ford.catalog.datasource.password` resolves `${FORD_CATALOG_PASSWORD:${FORD_DATABASE_PASSWORD:}}`; there is no catalog-password OCID input.

- [ ] Add a focused configuration test proving the existing database password is used when `FORD_CATALOG_PASSWORD` is absent:

Add this import to `CatalogDataSourceConfigurationTest.java`:

```java
import com.ford.service.catalog.adapter.out.CatalogDataSourceProperties;
```

Add this method to the test class:

```java
@Test
void catalogPasswordFallsBackToExistingDatabasePassword() {
    contextRunner.withPropertyValues(
                    "ford.catalog.tools.enabled=true",
                    "ford.catalog.datasource.url=jdbc:h2:mem:catalog-password-fallback",
                    "ford.catalog.datasource.username=ford_catalog_reader",
                    "ford.catalog.datasource.password=${FORD_DATABASE_PASSWORD}",
                    "FORD_DATABASE_PASSWORD=existing-db-password",
                    "ford.catalog.datasource.driver-class-name=org.h2.Driver")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CatalogDataSourceProperties.class).password())
                        .isEqualTo("existing-db-password");
            });
}
```

- [ ] Run `./gradlew test --tests 'com.ford.service.catalog.CatalogDataSourceConfigurationTest'`; observe the new fallback test fail against `password: ${FORD_CATALOG_PASSWORD:}`.
- [ ] Change the application property to `password: ${FORD_CATALOG_PASSWORD:${FORD_DATABASE_PASSWORD:}}`; retain `CatalogDataSourceProperties.validate()` so enabled tools still fail if both properties are blank.
- [ ] Remove `service-catalog-password-secret-ocid` and `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` action plumbing. Keep only the disabled-by-default feature flag, JDBC URL, and `ford_catalog_reader` username.
- [ ] Remove catalog-password OCID validation from `.github/actions/oci-blue-green/deploy.sh`; enabled catalog tools still require a PostgreSQL URL and dedicated reader username.
- [ ] Update Service `release.yml` and its contracts to require the existing database password OCID (already required for Service) and never require a second catalog secret.
- [ ] Run the focused Gradle test, all Action Python contracts, all Service Python contracts, Node workflow contracts, and `git diff --check`; all pass.

### Task 2: Provision and verify the SELECT-only login during the scheduler rollout

**Files:**
- Modify: `.github/actions/oci-blue-green/action.yml`
- Modify: `.github/actions/oci-blue-green/deploy.sh`
- Modify: `deployment/ansible/roles/scraper_scheduler/tasks/release.yml`
- Create: `deployment/ansible/roles/scraper_scheduler/templates/scheduler.env.j2`
- Modify: `deployment/ansible/roles/scraper_scheduler/templates/compose.yml.j2`
- Modify: `.github/workflows/release.yml` in Ford-scraping
- Test: `tests/test_action_package_contract.py`
- Validate: `deployment/ansible/scraper-scheduler-blue-green.yml`

**Interfaces:**
- Consumes: Candidate image and the existing `FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD` reference, read with emme-2's already-authorized Instance Principal.
- Produces: A mode-0600 scheduler environment and `ford_catalog_reader` login with the same existing password value as `ford`, view-only grants, a verified read/write-denied result, and a release manifest for the coordinator.

- [ ] Add an Action contract test requiring reader setup after `Migrate the catalog` and before `Replace the single active scheduler`; require `no_log: true` for the environment slurp, password fact, and psql role command.
- [ ] Run `python3 -m unittest tests.test_action_package_contract.ActionPackageContractTest.test_scheduler_reader_is_provisioned_after_migrations`; observe failure before the role task exists.
- [ ] Add `scheduler-postgres-password-secret-ocid` to the action, map it to `OCI_SCHEDULER_POSTGRES_PASSWORD_SECRET_OCID`, and wire the existing `FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD` production variable from Ford-scraping `release.yml`. Validate the OCID for scheduler deploy/rollback; create no new secret.
- [ ] Install/verify OCI CLI on emme-2 and fetch that existing secret with `oci secrets secret-bundle get --auth instance_principal --secret-id "$OCI_SCHEDULER_POSTGRES_PASSWORD_SECRET_OCID"`; register the base64 content `no_log: true`, decode into `scheduler_database_password`, and assert it is non-empty.
- [ ] Render `scheduler.env.j2` to `{{ remote_root }}/mcp/scheduler.env` with only `POSTGRES_PASSWORD={{ scheduler_database_password }}`, owner `ansible_user`, mode `0600`, and `no_log: true`. Change scheduler migration/start/rollback Compose commands to use `scheduler.env`, removing the dependency on the old MCP `.env`.
- [ ] Execute psql inside `data-postgres-1` as the container's `postgres` OS/database user, with `catalog_reader_password` passed by psql's quoted variable form and task output hidden. Create the role only if absent; otherwise reassert its login flags/password:

```sql
SELECT format(
  'CREATE ROLE ford_catalog_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION PASSWORD %L',
  :'catalog_reader_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ford_catalog_reader')
\gexec

ALTER ROLE ford_catalog_reader
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION
  PASSWORD :'catalog_reader_password';
DO $catalog_reader_memberships$
DECLARE granted_role text;
BEGIN
  FOR granted_role IN
    SELECT granted.rolname
    FROM pg_auth_members AS memberships
    JOIN pg_roles AS granted ON granted.oid = memberships.roleid
    JOIN pg_roles AS member ON member.oid = memberships.member
    WHERE member.rolname = 'ford_catalog_reader'
  LOOP
    EXECUTE format('REVOKE %I FROM ford_catalog_reader', granted_role);
  END LOOP;
END
$catalog_reader_memberships$;
REVOKE ALL PRIVILEGES ON SCHEMA ford FROM ford_catalog_reader;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA ford FROM ford_catalog_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA ford FROM ford_catalog_reader;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA ford_read FROM ford_catalog_reader;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA ford_read FROM ford_catalog_reader;
GRANT CONNECT ON DATABASE ford_service TO ford_catalog_reader;
GRANT USAGE ON SCHEMA ford_read TO ford_catalog_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA ford_read TO ford_catalog_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE ford IN SCHEMA ford_read
  REVOKE ALL ON TABLES FROM ford_catalog_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE ford IN SCHEMA ford_read
  GRANT SELECT ON TABLES TO ford_catalog_reader;
ALTER ROLE ford_catalog_reader SET default_transaction_read_only = on;
```

- [ ] Verify `ford_catalog_reader` executes a read-only SELECT from `ford_read.vehicle_search_v1`, has SELECT but no INSERT/UPDATE/DELETE/TRUNCATE, has no USAGE on base schema `ford`, and has no role memberships.
- [ ] If migration or reader verification fails, fail before replacing the active scheduler. Leave the existing scheduler active; additive read-view migrations and the verified backup remain available.
- [ ] Run every Action Python test, `bash -n .github/actions/oci-blue-green/deploy.sh`, Ansible `--syntax-check`, Ansible `--list-tasks`, and `git diff --check`.

### Task 3: Set production catalog inputs without a new secret

**Files:**
- Modify: `ford-ai-service/README.md`
- Modify: `docs/operations/catalog-read-only-access.md`
- Modify: `ford-ai-service/scripts/tests/test_oci_action_contract.py`
- Modify: `scripts/workflow-contract.test.mjs`

- [ ] Document that the existing `FORD_DATABASE_PASSWORD` Vault value is reused with `FORD_CATALOG_USERNAME=ford_catalog_reader`; no catalog password mapping is required.
- [ ] Keep `FORD_CATALOG_TOOLS_ENABLED=false` by default. Set it true only after the protected scheduler release verifies reader privileges.
- [ ] Set the protected Service variables to `FORD_CATALOG_TOOLS_ENABLED=true`, `FORD_CATALOG_JDBC_URL=jdbc:postgresql://10.0.0.29:5432/ford_service?ApplicationName=ford-service-catalog&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true`, and `FORD_CATALOG_USERNAME=ford_catalog_reader`.
- [ ] Verify Service release validation still requires the existing database password OCID but no separate catalog secret. Run Service Python/workflow contracts and `git diff --check`.

### Task 4: Protected deployment and live verification

**Files:**
- Run: protected scheduler release from Ford-scraping.
- Run: `mise run deploy:release-all -- v0.1.25 v0.1.12 v0.1.4` after code merges/tags and the reader setup is merged.

- [ ] Confirm the main-branch read-only preflight passes: emme-1 Service/Collector, emme-2 PostgreSQL, exactly one running scheduler, and the live Compose root from Docker labels.
- [ ] Release scheduler first; confirm backup precedes migrations, the reader SELECT succeeds, INSERT is denied, the monthly schedule is active, and the action artifact records active/prior immutable images.
- [ ] Only after role verification, enable the three Service catalog variables and run the protected coordinated Service/Scraping/Web release. Complete `release-all-approval` review.
- [ ] Verify Service health, catalog evidence/source URL, Web Pages/API/assets, one scheduler, current backup, and rollback image. Do not message a real customer.
- [ ] Keep old MCP containers/routes/secrets until the Service catalog smoke and rollback window pass; clean up only through the separately protected cleanup procedure.

## Acceptance Criteria

- No new Vault secret, OCID, GitHub secret, or Terraform secret grant.
- `ford_catalog_reader` can read `ford_read` views and cannot write; base `ford` schema remains inaccessible to this login.
- Spring catalog datasource uses the existing database password secret with the dedicated reader username and configured URL.
- Production catalog tools stay off until reader verification succeeds, then enable only in the protected Service release.
- Scheduler migration, role provisioning, active-schedule health, rollback, and both-host release evidence pass.
