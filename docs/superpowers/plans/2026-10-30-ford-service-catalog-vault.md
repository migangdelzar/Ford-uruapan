# Ford Service Catalog Vault Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the internal Ford Service catalog datasource fetch its dedicated read-only `ford_catalog` password from OCI Vault without exposing the value to GitHub Actions, files, or logs.

**Architecture:** Add a small OCI Secret Bundle adapter and catalog password resolver in Ford Service. Plumb only the secret OCID through the shared OCI deployment action, and grant the app instance principal access only to the named reader-password secret through the existing protected Terraform policy.

**Tech Stack:** Java 25, Spring Boot, OCI Java SDK 3.97.2, Gradle, Python `unittest`, Ansible/Jinja, Terraform.

## Global Constraints

- Use the separate production `ford_catalog` database and dedicated `ford_catalog_reader` role; never use `ford_service` or the `ford` application/admin role.
- Keep `FORD_CATALOG_TOOLS_ENABLED=false` until the reader account, secret, production candidate, database backup/restore, rollback, and exact-SHA release gates pass.
- Production password comes only from OCI Vault in `oci-vault` mode; never pass its value through GitHub Actions, persist it, or log it.
- Disabled tools must not initialize the catalog datasource or contact Vault.
- Vault IAM grants access only to `ford-prod-catalog-reader-password` for the app instance principal; apply only through the protected Terraform workflow after plan review.
- Do not create or alter production database roles, create the Vault secret, publish catalog data, change routing, or deploy as part of implementation.

---

## File Structure

### Ford-uruapan repository

- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceProperties.java` — catalog URL, reader identity, provider, local password, and Vault secret OCID validation.
- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogSecretBundle.java` — immutable content-type and base64 payload returned by the OCI adapter.
- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogSecretBundleClient.java` — narrow interface for retrieving one OCI secret bundle.
- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/OciCatalogSecretBundleClient.java` — OCI SDK implementation using instance principals.
- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogPasswordResolver.java` — validates and decodes only base64 OCI secret content.
- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogVaultConfiguration.java` — creates/closes the OCI client only when catalog tools and OCI Vault mode are enabled.
- `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceConfiguration.java` — resolves the password before building the independent datasource.
- `ford-ai-service/src/main/resources/application.yml` — binds `FORD_SECRETS_PROVIDER` and `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD`.
- `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogPasswordResolverTest.java` — base64 decoding and fail-closed tests.
- `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogDataSourceConfigurationTest.java` — enabled/disabled and datasource validation tests.
- `deployment/terraform/runtime-vault-policy.json` — exact app-only secret-name allowlist.
- `ford-ai-service/scripts/tests/test_terraform_runtime_vault_plan_guard.py` — exact secret grant contract.
- `ford-ai-service/scripts/tests/test_oci_action_contract.py` — release-to-action OCID wiring contract.
- `.github/workflows/release.yml` — protected variable-to-action wiring and release validation.

### Ford-oci-deployment-action repository

- `.github/actions/oci-blue-green/action.yml` — declares and forwards the catalog password secret OCID input.
- `.github/actions/oci-blue-green/deploy.sh` — validates the OCID and passes it as an Ansible extra var; never receives the password.
- `deployment/ansible/roles/app/templates/app.env.j2` — writes only the OCID into the protected application env file.
- `tests/test_action_package_contract.py` — verifies validation, pass-through, and no secret-value path.

## Task 1: Define Catalog Vault Configuration Contract

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceProperties.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogDataSourceConfigurationTest.java`
- Modify: `ford-ai-service/src/main/resources/application.yml`

**Interfaces:**
- Bind `FORD_SECRETS_PROVIDER` to `ford.catalog.datasource.secrets-provider`, default `environment`.
- Bind `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` to `ford.catalog.datasource.password-secret-ocid`.
- Preserve local `FORD_CATALOG_PASSWORD` support only for the `environment` provider.
- In `oci-vault` mode, require a valid OCI Vault secret OCID, PostgreSQL URL whose database is `ford_catalog`, and username exactly `ford_catalog_reader`. Require no environment password fallback.

- [ ] Add failing tests for missing OCID, wrong username, `ford_service` URL, wrong database, and environment-password fallback in `oci-vault` mode.
- [ ] Run `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.catalog.CatalogDataSourceConfigurationTest`.
  - Expected: the new tests fail before implementation.
- [ ] Add provider and secret-OCID properties; make validation provider-aware while retaining existing H2 and local environment tests.
- [ ] Run the focused test command again.
  - Expected: all `CatalogDataSourceConfigurationTest` tests pass.
- [ ] Commit as `feat: validate catalog Vault configuration`.

## Task 2: Implement OCI Secret Bundle Resolution

**Files:**
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogSecretBundle.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogSecretBundleClient.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/OciCatalogSecretBundleClient.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogPasswordResolver.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogVaultConfiguration.java`
- Create: `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogPasswordResolverTest.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceConfiguration.java`

**Interfaces:**
- `CatalogSecretBundle(String contentType, String content)` carries bundle metadata and base64 payload only.
- `CatalogSecretBundleClient.getSecretBundle(String secretOcid)` returns one bundle or throws a sanitized runtime exception.
- `CatalogPasswordResolver.resolve(String secretOcid)` returns a non-empty UTF-8 password only when content type is `BASE64` and decoding succeeds.
- `OciCatalogSecretBundleClient` uses `SecretsClient` and `InstancePrincipalsAuthenticationDetailsProvider`; the client is closed by Spring.

- [ ] Add failing resolver tests: valid base64, wrong content type, invalid base64, invalid UTF-8, empty password, missing OCID, and OCI client exception.
- [ ] Run `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.catalog.CatalogPasswordResolverTest`.
  - Expected: tests fail because resolver types do not exist.
- [ ] Implement the bundle interface, SDK adapter, and resolver. Error messages may name the failing operation, never include OCID response bodies or secret content.
- [ ] Run the focused resolver test.
  - Expected: all resolver tests pass and no OCI network call occurs in unit tests.
- [ ] Commit as `feat: resolve catalog reader password from OCI Vault`.

## Task 3: Wire Resolver Only Into Enabled Catalog Datasource

**Files:**
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogVaultConfiguration.java`
- Modify: `ford-ai-service/src/main/java/com/ford/service/catalog/adapter/out/CatalogDataSourceConfiguration.java`
- Modify: `ford-ai-service/src/test/java/com/ford/service/catalog/CatalogDataSourceConfigurationTest.java`

**Interfaces:**
- `CatalogVaultConfiguration` is conditional on both `ford.catalog.tools.enabled=true` and `ford.catalog.datasource.secrets-provider=oci-vault`.
- `CatalogDataSourceConfiguration` uses `CatalogPasswordResolver` only in OCI Vault mode; local environment mode continues using `FORD_CATALOG_PASSWORD`.

- [ ] Add failing tests proving disabled mode creates no datasource and never calls a password resolver.
- [ ] Add failing tests proving OCI Vault mode resolves the configured OCID once and passes the returned password only to the catalog datasource.
- [ ] Add failing tests proving a Vault exception or blank result prevents datasource startup and does not expose secret text in the exception.
- [ ] Run `cd ford-ai-service && ./gradlew test --no-daemon --tests com.ford.service.catalog.CatalogDataSourceConfigurationTest`.
  - Expected: new tests fail before the wiring change.
- [ ] Implement conditional configuration and datasource password selection.
- [ ] Run focused catalog tests.
  - Expected: existing local/H2 behavior passes; disabled mode performs no Vault access; enabled Vault mode fails closed.
- [ ] Commit as `feat: wire Vault password into catalog datasource`.

## Task 4: Add Least-Privilege Runtime Vault Grant

**Files:**
- Modify: `deployment/terraform/runtime-vault-policy.json`
- Modify: `ford-ai-service/scripts/tests/test_terraform_runtime_vault_plan_guard.py`

**Interfaces:**
- Add `ford-prod-catalog-reader-password` to `secret_names.app` only.
- Keep the existing policy generator and full-plan guard as the only policy path; no direct OCI policy command or Terraform apply.

- [ ] Add a failing test asserting the catalog reader secret is granted to `app` and absent from `data`.
- [ ] Run `python3 -m unittest discover -s ford-ai-service/scripts/tests -p 'test_terraform_runtime_vault_plan_guard.py'`.
  - Expected: the new secret-name assertion fails.
- [ ] Add the single secret name to `runtime-vault-policy.json`.
- [ ] Run the focused test again.
  - Expected: the full plan guard tests pass, including exact statement-set and no-broadening checks.
- [ ] Commit as `ops: allow app identity to read catalog reader secret`.

## Task 5: Plumb Secret OCID Through Shared Deployment Action

**Repository:** `Ford-oci-deployment-action`, on a new isolated branch from its current protected main.

**Files:**
- Modify: `.github/actions/oci-blue-green/action.yml`
- Modify: `.github/actions/oci-blue-green/deploy.sh`
- Modify: `deployment/ansible/roles/app/templates/app.env.j2`
- Modify: `tests/test_action_package_contract.py`

**Interfaces:**
- Add input `service-catalog-password-secret-ocid`.
- Forward it as `OCI_SERVICE_CATALOG_PASSWORD_SECRET_OCID` and Ansible var `ford_catalog_password_secret_ocid`.
- When catalog tools are enabled, require `FORD_SECRETS_PROVIDER=oci-vault` and an OCID reference matching the action's lexical shape `^ocid1[.]vaultsecret[.][A-Za-z0-9._-]+$`; Java validates the full structural OCI secret OCID.
- The app env template writes `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` only; it never receives or writes a password value.

- [ ] Add action contract assertions for enabled-mode OCID validation, exact action-input forwarding, template rendering of the reference, and absence of a password-value input.
- [ ] Run `python3 -m unittest discover -s tests -p 'test_action_package_contract.py'` in the action repository.
  - Expected: new assertions fail before implementation.
- [ ] Add the input and environment/Ansible mapping; add fail-closed validation and render the OCID into `app.env.j2`.
- [ ] Run the action contract suite and `git diff --check`.
  - Expected: all action package tests pass; diff check is clean.
- [ ] Commit and open a reviewable PR in `Ford-oci-deployment-action`; do not release or deploy the action before checks pass.

## Task 6: Pin Shared Action and Wire Protected Ford Service Release

**Repository:** `Ford-uruapan`, after the shared-action PR is merged and reviewed.

**Files:**
- Modify: `.github/workflows/release.yml`
- Modify: `ford-ai-service/scripts/tests/test_oci_action_contract.py`

**Interfaces:**
- Read protected variable `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` from the production environment.
- Pass only that OCID as `service-catalog-password-secret-ocid`.
- Pin the action to the exact 40-character merge commit of the reviewed shared-action PR.
- Keep the existing production enable flag default false and existing reader/backup/release gates intact.

- [ ] Add failing contract assertions for the secret-OCID variable, action input, immutable action SHA, and false-by-default catalog flag.
- [ ] Run `cd ford-ai-service && python3 -m unittest scripts.tests.test_oci_action_contract`.
  - Expected: new wiring assertions fail before the workflow change.
- [ ] Add the protected variable and action input; update the action pin to the verified merged action commit SHA.
- [ ] Run the focused Python contract test and `python3 -m unittest discover -s ford-ai-service/scripts/tests -p 'test_*.py'`.
  - Expected: focused contract and full Python contract suites pass.
- [ ] Commit as `ops: wire catalog Vault reference into Service release` and open a PR.

## Task 7: Verify Code and Prepare Protected Gates

**Files:**
- Verify changes in both repositories; no production files or database data are changed by this task.

- [ ] Run `cd ford-ai-service && ./gradlew test --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=''`.
  - Expected: `BUILD SUCCESSFUL`.
- [ ] Run `cd ford-ai-service && ./gradlew integrationTest --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=''` against disposable PostgreSQL only.
  - Expected: tests pass; no production host or database is used.
- [ ] Run the complete Python contract suite, the shared-action package tests, Terraform formatting/validation, workflow YAML parsing, and `git diff --check`.
  - Expected: all checks pass on the exact PR SHAs.
- [ ] After both PRs merge, require green CI on their exact merge SHAs. Run the existing Terraform workflow in plan mode and inspect its guarded artifact; stop before apply until a separate protected approval.
- [ ] Stop before production enablement unless an operator has created the dedicated `ford_catalog_reader` role and named OCI secret, configured the protected secret OCID and JDBC URL/user, populated and reviewed production catalog data, verified the app instance-principal path, and passed backup/restore/rollback and release gates.

## Stop Conditions

- Missing production `ford_catalog` endpoint, reader account, secret OCID, or app Vault policy access: stop; do not use `postgres`, `ford`, or another writer/admin identity.
- Any resolver test failure, broadened IAM statement, raw secret output, migration uncertainty, or mismatch between candidate and production inventory: stop; do not enable the tool.
- This plan does not authorize deployment. Production enablement and deployment require all existing catalog replacement and full-stack rollout gates, exact-main artifacts, and protected approvals.
