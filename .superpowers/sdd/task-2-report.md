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
