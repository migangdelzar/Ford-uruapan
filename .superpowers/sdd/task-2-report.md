## Task 2 — OCI Catalog Password Resolution

### Subtitle: Resolve catalog password from OCI Vault without exposing secret data

### Task: Add resolver and OCI adapter

- [x] Add password resolver checks and implementation
  - **Actions Applied**
    - `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogPasswordResolverTest.java` — test BASE64 UTF-8 success, unsupported type, malformed base64/UTF-8, empty password, missing OCID, and sanitized client failure.
    - Added `CatalogSecretBundle`, `CatalogSecretBundleClient`, `CatalogPasswordResolver`, `OciCatalogSecretBundleClient`, and `CatalogVaultConfiguration` under `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/`.
    - `CatalogDataSourceConfiguration.java` — supply resolved Vault password for OCI mode.
  - **Verification**
    - RED: focused Gradle run failed compilation because resolver/bundle types did not exist.
    - GREEN: `./gradlew test --no-daemon --tests com.ford.service.catalog.CatalogPasswordResolverTest` — passed, 7 tests.
    - Full `./gradlew test --no-daemon` — passed, 1383 tests.

### Summary — Current Status

Resolver and SDK adapter implemented. Resolver errors omit OCID response bodies, secret contents, and raw SDK causes.

### Next Up

- Review deployment runtime permissions and OCI region setup in the separate deployment task.

## Review Fixes — 2026-10-30

### Subtitle: Gate OCI client creation and fail closed on missing resolver

### Task: Address Task 2 review findings

- [x] Add regressions before production changes
  - **Actions Applied**
    - `CatalogDataSourceConfigurationTest.java` — assert disabled catalog tools plus OCI provider creates no Vault client; assert OCI datasource fails safely without resolver; provide a fake resolver in valid-configuration coverage.
    - `CatalogPasswordResolverTest.java` — use OCI SDK `BmcException` with sensitive response text for sanitization check.
  - **Verification**
    - RED: focused tests exposed the missing-resolver fallback; initially, disabled-tools test also stalled while ungated OCI auth construction was attempted.
- [x] Gate client wiring and sanitize documented SDK failures
  - **Actions Applied**
    - `CatalogVaultConfiguration.java` — condition on both catalog tools enabled and `secrets-provider=oci-vault`.
    - `CatalogDataSourceConfiguration.java` — throw sanitized configuration error when OCI mode has no resolver.
    - `OciCatalogSecretBundleClient.java` — catch SDK `BmcException`; remove unused factory so Spring owns client lifecycle.
  - **Verification**
    - GREEN: focused resolver and datasource config tests — passed.
    - GREEN: full `./gradlew test --no-daemon` — passed, 1,385 tests.

### Summary — Current Status

All review findings addressed. No SDK calls occur when catalog tools are disabled; OCI datasource setup fails closed without resolver. SDK exceptions are sanitized; fatal JVM errors are not caught.

### Next Up

- None for Task 2.
