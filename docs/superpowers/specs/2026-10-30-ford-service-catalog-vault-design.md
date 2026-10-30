# Ford Service Catalog Vault Integration Design

## Goal

Enable Ford Service's internal, read-only catalog tool to connect to the
separate production `ford_catalog` database using a dedicated reader credential
from OCI Vault. This is credential plumbing only; it does not publish catalog
data or enable the feature in production.

## Current State

- `FORD_CATALOG_TOOLS_ENABLED` is false and both Service slots lack catalog
  URL, username, and password configuration.
- `CatalogDataSourceProperties` requires non-empty URL, username, and password,
  and rejects `ford_service` as the catalog database.
- The Service deploy action passes the catalog enable flag, URL, and username,
  but no catalog password secret reference.
- The app source has no catalog-specific OCI Vault resolver. The Terraform
  runtime Vault allowlist does not include a catalog reader secret.
- The target is a separate `ford_catalog` database. The reader must be
  `ford_catalog_reader`; the runtime role must have read-only privileges.

## Design

### Secret resolution

- Add a catalog-only secret resolver behind an interface. In production
  (`FORD_SECRETS_PROVIDER=oci-vault`), it uses OCI Instance Principals and the
  OCI Secrets SDK to retrieve the configured secret bundle at Service startup.
- Decode only OCI `BASE64` secret content. Reject missing references, invalid
  content type, empty values, malformed content, and Vault/IAM failures.
- Resolve the password only when catalog tools are enabled. Disabled mode must
  not construct the catalog datasource or contact Vault.
- Keep the resolved password in process memory only. Never log the value, write
  it to a file, pass it through GitHub Actions, or include it in diagnostics.
- Preserve explicit `FORD_CATALOG_PASSWORD` configuration for local/test
  environments using the `environment` provider. Production OCI Vault mode must
  not fall back to an environment password or a default credential.

### Deployment and IAM wiring

- Add a `service-catalog-password-secret-ocid` input to the shared OCI deploy
  action and pass it as `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` into the
  Service environment file. The file contains only the secret OCID.
- Add the corresponding protected production variable to the Ford Service
  release workflow; do not add any secret value to GitHub variables or source.
- Add the exact catalog reader secret name
  `ford-prod-catalog-reader-password` to the app instance's runtime Vault
  policy allowlist. Terraform plan review and protected apply remain separate
  gates; this change must not apply policy automatically.
- Require a JDBC URL targeting the separate `ford_catalog` database and the
  dedicated `ford_catalog_reader` username. Never point this datasource at
  `ford_service` or use the `ford` application/admin role.

### Failure behavior

- If tools are disabled, startup does not resolve the secret or initialize the
  catalog datasource.
- If tools are enabled but URL, reader identity, secret reference, instance
  identity, or Vault access is invalid, startup fails closed. No fallback to
  writable credentials.
- Existing Ford Service health remains the release gate. Catalog readiness is
  checked only when the tool is enabled; health output contains statuses only.

## Verification

- Unit tests cover disabled-mode no-fetch, successful decoding, non-BASE64 and
  malformed content, missing/invalid secret references, Vault/IAM errors, and
  password-free diagnostics. OCI access is mocked in unit tests.
- Configuration tests reject `ford_service`, non-reader usernames, blank
  passwords, and incomplete production Vault configuration.
- Deployment contract tests verify that only the OCID is passed to the host and
  that the Terraform policy grants access only to the named catalog secret.
- Integration tests use a disposable PostgreSQL `ford_catalog` database with a
  reader role; prove expected SELECT access and denied writes. No production DB
  is used in tests.
- Before production enablement, separately verify the real database/role,
  candidate publication and read views, Vault secret existence, app instance
  principal access, backup/restore, rollback, and exact-SHA release gates.

## Explicitly Out of Scope

- Creating or changing the production database role or password.
- Creating the OCI Vault secret or applying IAM policy.
- Publishing/replacing catalog rows or enabling catalog tools in production.
- Deploying Ford Service, Ford-web, or the scraper scheduler.
- Any routing, firewall, customer-data, or non-catalog database changes.

## Acceptance Criteria

1. Production Service obtains the catalog reader password only from the named
   OCI Vault secret through the app instance principal.
2. Disabled catalog tools cause no Vault access and no catalog datasource
   initialization.
3. Enabled catalog tools fail closed unless the separate `ford_catalog`
   endpoint, dedicated reader, and secret are all valid.
4. Tests prove no secret value is logged, persisted, or sent through GitHub
   Actions.
5. Production remains unchanged until all separate publication and release
   gates pass.
