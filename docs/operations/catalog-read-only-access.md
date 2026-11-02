# Ford catalog read-only access

Ford-scraping owns the `ford` schema, migrations, crawler, and all catalog writes. Ford Service uses a separate JDBC pool and a dedicated PostgreSQL reader role. Do not point the Service's primary `FORD_DATABASE_*` connection at the catalog database: its Liquibase migrations belong to the Service database.

## Runtime settings

Configure these only when preparing an approved catalog-tool rollout:

- `FORD_CATALOG_TOOLS_ENABLED=false` by default and when unset (fail-closed). Local fixture diagnostics may opt in only with a separately configured test-only read-only datasource in a disposable environment. Keep production explicitly false through migration and verification; protected production configuration may explicitly set true only after cutover gates 1–8 and approvals pass. Explicit false remains the emergency disable switch.
- `FORD_CATALOG_JDBC_URL=jdbc:postgresql://<catalog-host>:5432/<catalog-db>?connectTimeout=5&socketTimeout=30&tcpKeepAlive=true`
- `FORD_CATALOG_USERNAME=<read-only-role>`
- The catalog datasource uses `FORD_CATALOG_DATABASE_PASSWORD`, mapped in `ford-ai-service/src/main/resources/application.yml` from `FORD_VAULT_SECRET_OCID_FORD_CATALOG_DATABASE_PASSWORD`. This is distinct from `FORD_DATABASE_PASSWORD`; review the catalog-specific secret identity, Vault access, and least-privilege reader role without printing secret values.
- `FORD_CATALOG_DRIVER=org.postgresql.Driver` (default)
- `FORD_CATALOG_READ_ONLY_TOOL_NAMES` optionally narrows the built-in read-only tool allowlist.

When enabled, startup fails if URL, username, or `FORD_CATALOG_DATABASE_PASSWORD` is missing. The catalog adapter uses a separate JDBC datasource and the `ford_catalog_reader` login; its catalog-specific password is resolved through the mapping above. It does not use the primary `ford` login. Review secret ownership and access before rollout. JDBC URL timeouts bound connection and query waits.

## Customer replies and recovery

- Price and promotion replies use only current-turn catalog evidence. Keep them brief; include requested price/currency, promotion benefit, validity, material conditions, and at most one official source link.
- Retry transient catalog callback errors once inside the callback. Longer retry-after requests use the durable inbound recovery path instead of waiting on the customer reply.
- If a transient failed lookup leaves no verified answer, do not send a failure message and do not commit the bot turn. The inbound event remains pending for at most five recovery attempts; unresolved turns remain available for operations review.
- Authentication, authorization, and invalid-request failures skip automatic retry and remain uncommitted for review. Never use conversation memory as current price or promotion evidence.
- A successful catalog query with zero matching results may send one short, grounded no-result reply. This differs from a tool failure.
- Append after-hours or holiday-calendar detail only when the customer asks about hours/special hours or explicitly requests team follow-up. Do not append it to greetings or catalog answers.

## PostgreSQL grants

Create a dedicated login role and grant only schema usage and SELECT on the versioned views. Replace role names using the deployment's database-owner conventions; do not grant this role to the Service migration account.

```sql
CREATE ROLE ford_catalog_reader LOGIN;
GRANT CONNECT ON DATABASE ford_catalog TO ford_catalog_reader;
GRANT USAGE ON SCHEMA ford_read TO ford_catalog_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA ford_read TO ford_catalog_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE ford_catalog_owner IN SCHEMA ford_read
  GRANT SELECT ON TABLES TO ford_catalog_reader;
```

The adapter reads versioned `ford_read` views, including vehicle search/variants, `vehicle_facts_v2`, offers/promotions, links/images/comparisons, provenance, and catalog status. It issues no DDL or writes. The grant does not expose base `ford` tables.

For exact vehicle details, `technicalSheetUrl` is selected from the exact source URL of a model-level specification fact whose URL pathname contains `ficha-tecnica`, matching Ford-scraping's page classifier. The adapter returns that source URL unchanged and keeps it separate from the price source. Search results omit this detail-only field when no such evidence is present.

## Rollback

Disable catalog callbacks with `FORD_CATALOG_TOOLS_ENABLED=false` and restart Ford Service. This does not stop Ford-scraping or change catalog data. During transition, retain the prior deployment and tunnel/secret resources until the approved rollout verifies Service behavior. No resource cleanup is part of this repository change.

Before any production enablement: clear the ordered cutover gates in `docs/superpowers/plans/2026-11-01-ford-database-cutover-release.md`, run the Service-owned catalog tool and adapter tests, verify the reader role can query required views and cannot write/private-read, run Service unit tests and `bootJar`, and perform approved synthetic checks. Production remains explicitly false through migration and verification; only the final protected activation gate may set it true. Do not interpret local fixture diagnostics as production evidence. Node/MCP output parity is not a prerequisite.

Optional historical adapter verification can be reproduced with the disposable snapshot harness. It restores a Ford-scraping dump into a throwaway container, applies the `ford_read` view migrations, runs `CatalogSnapshotParityIntegrationTest` against real rows, then removes the container. This is useful datasource/adapter coverage, not a Node/MCP parity gate:

```bash
cd ford-ai-service
mise run test:parity -- /path/to/ford-<timestamp>.dump
```
