# Catalog Reader Without a New Vault Secret

## Goal

Enable Ford Service's in-process Spring AI catalog tools with database-enforced read-only access, without creating a second database password secret in OCI Vault.

## Approved decision

Reuse the existing `FORD_DATABASE_PASSWORD` secret value for a separate PostgreSQL login named `ford_catalog_reader`. The login receives only `CONNECT`, `USAGE` on `ford_read`, and `SELECT` on current/future `ford_read` views. Provisioning removes existing role memberships and any prior base-schema/write grants before applying the allowlist. Ford Service's existing OCI Vault property loader already places `FORD_DATABASE_PASSWORD` in the same JVM; the catalog datasource uses that value with the reader username. No new Vault secret, OCID, GitHub secret, or Terraform secret-name grant is introduced.

The shared password does not make the reader login writable: PostgreSQL privileges follow the login role. The Service process already has the primary `ford` writer credential for conversation persistence, so this reader boundary limits the catalog adapter's connection and SQL capabilities, not a compromised Service process.

## Ownership and rollout sequence

1. Ford-scraping remains the sole schema/migration/catalog writer. Its protected scheduler release first verifies a database backup and applies forward-only catalog read-view migrations.
2. The scheduler and migration containers receive only the existing `FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD`, database host/user/name, and `FORD_SECRETS_PROVIDER=oci-vault`; the existing Node runtime resolves the password in-process and builds `DATABASE_URL`. After the backup and migrations, Ansible reads the same secret on emme-2 with Instance Principal, passes it transiently to psql under `no_log`, provisions or repairs `ford_catalog_reader`, removes inherited memberships and prior broad grants, applies only the documented allowlist, and verifies `SELECT` succeeds while writes and base-schema access are denied. No password file is written.
3. If migration, role creation, or permission verification fails, the existing scheduler stays active. The replacement scheduler does not start and Service is not cut over.
4. Ford Service binds its catalog datasource to the existing `FORD_DATABASE_PASSWORD` value, `FORD_CATALOG_USERNAME=ford_catalog_reader`, and the configured private JDBC URL. `FORD_CATALOG_TOOLS_ENABLED` remains false by default and is enabled only after the protected reader check passes.
5. The protected Service release performs a read-only catalog smoke (vehicle evidence and source URL) before the old MCP consumer is considered for retirement. On failure, roll back the Service image or disable the flag; keep the old MCP endpoint/resources until the Service smoke passes.

## Configuration contract

- Keep the existing `FORD_VAULT_SECRET_OCID_FORD_DATABASE_PASSWORD` and `FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD`; do not add `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` or any replacement secret. Reuse the existing data-host PostgreSQL OCID when provisioning the reader; Service's catalog datasource uses its already loaded `FORD_DATABASE_PASSWORD` value.
- `FORD_CATALOG_JDBC_URL` targets the existing Ford database on emme-2 and the current catalog schema/views.
- `FORD_CATALOG_USERNAME` is exactly `ford_catalog_reader`.
- The catalog datasource password is the already-loaded `FORD_DATABASE_PASSWORD` property. It must fail closed if that property is blank; do not configure a separate `FORD_CATALOG_PASSWORD` setting.
- Keep the adapter restricted to parameterized reads from `ford_read`; it must not use the primary `ford` datasource.
- Do not turn on the catalog feature flag merely because the scheduler release succeeded; require the SELECT/denied-write proof and protected Service smoke.

## Verification and rollback

- Unit/config tests verify the catalog datasource reuses the existing database password property, requires the dedicated reader username, and remains disabled by default.
- PostgreSQL integration tests create the reader role with the same password value as the owner test role, verify the expected read views are selectable, verify `INSERT`/`UPDATE`/DDL are denied, and verify no grants exist on base `ford` tables.
- The protected scheduler release resolves the existing database password through the Node OCI Vault provider and a hidden Ansible task for role setup; it never writes the password to disk or logs it.
- The protected Service release verifies catalog tool evidence, then keeps the flag enabled only if the smoke succeeds. If it fails, use the existing immutable Service rollback or set the catalog flag false; do not remove the old MCP footprint.

## Out of scope

- Creating or rotating a second Vault password secret.
- Giving Service catalog writes or exposing base `ford` tables.
- Node/MCP output parity as a release gate.
- Removing MCP routes, containers, or secrets before the Service catalog smoke passes.
