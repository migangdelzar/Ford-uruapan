# Ford catalog read-only access

Ford-scraping owns the `ford` schema, migrations, crawler, and all catalog writes. Ford Service uses a separate JDBC pool and a dedicated PostgreSQL reader role. Do not point the Service's primary `FORD_DATABASE_*` connection at the catalog database: its Liquibase migrations belong to the Service database.

## Runtime settings

Configure these only when preparing an approved catalog-tool rollout:

- `FORD_CATALOG_TOOLS_ENABLED=false` by default. Local development may opt in with a separately configured read-only datasource. Keep production false until the Service-owned tool configuration and datasource permissions are reviewed.
- `FORD_CATALOG_JDBC_URL=jdbc:postgresql://<catalog-host>:5432/<catalog-db>?connectTimeout=5&socketTimeout=30&tcpKeepAlive=true`
- `FORD_CATALOG_USERNAME=<read-only-role>`
- `FORD_CATALOG_PASSWORD=<secret-manager-injected-password>`
- `FORD_CATALOG_DRIVER=org.postgresql.Driver` (default)
- `FORD_CATALOG_READ_ONLY_TOOL_NAMES` optionally narrows the built-in read-only tool allowlist.

When enabled, startup fails if URL, username, or password is missing. There is no fallback to the Service datasource. Keep credentials in the deployment secret manager; never put values in repository files or CI logs. JDBC URL timeouts bound connection and query waits.

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

Before enabling: run the Service-owned catalog tool and adapter tests, verify the reader role can query required views and cannot write, run Service unit tests and `bootJar`, and perform a read-only smoke test. Node/MCP output parity is not a prerequisite. Production cutover requires protected release approval and a deployment record.

Optional historical adapter verification can be reproduced with the disposable snapshot harness. It restores a Ford-scraping dump into a throwaway container, applies the `ford_read` view migrations, runs `CatalogSnapshotParityIntegrationTest` against real rows, then removes the container. This is useful datasource/adapter coverage, not a Node/MCP parity gate:

```bash
cd ford-ai-service
mise run test:parity -- /path/to/ford-<timestamp>.dump
```
