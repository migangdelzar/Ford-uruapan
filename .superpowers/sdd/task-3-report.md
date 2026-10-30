## Task 3 — Vault Resolver Datasource Wiring

### TDD evidence

- Added tests for disabled mode (no catalog datasource, zero resolver calls), OCI mode (configured OCID resolved exactly once; returned password applied to the datasource but not stored in datasource properties), resolver failures, and blank Vault results.
- RED: `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.catalog.CatalogDataSourceConfigurationTest.vaultResolverFailurePreventsStartupWithoutExposingFailureText` — failed because raw resolver exception text was exposed instead of sanitized. The initial complete focused run also showed the blank result incorrectly allowed startup.
- GREEN: added sanitized handling around OCI resolver invocation and explicit rejection of null/blank resolved passwords in `CatalogDataSourceConfiguration`.
- Focused verification: `./gradlew test --no-daemon --tests com.ford.service.catalog.CatalogDataSourceConfigurationTest` — passed.
- Catalog verification: `./gradlew test --no-daemon --tests 'com.ford.service.catalog.*'` — passed.
- Full module verification: `./gradlew test --no-daemon` — passed.

### Result

Disabled tools do not create the datasource or invoke a supplied resolver. OCI mode resolves only the configured secret OCID once and uses the resolved password to build the catalog datasource. Resolver errors and blank results fail startup with sanitized diagnostics. Existing environment/H2 tests remain green.
