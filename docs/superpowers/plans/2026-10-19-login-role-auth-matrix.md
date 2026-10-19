# Ford Service Login Role Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add isolated PostgreSQL integration coverage for login/session behavior across `OPERATOR`, `MANAGER`, and `ADMIN`, while verifying configured password policy, neutral token-request responses, scope-based access, and tenant isolation.

**Architecture:** Extend the existing `OperatorAuthenticationConfigurationIntegrationTest` using its disposable PostgreSQL/Redis Testcontainers. Override only the email sender with a test capture bean so the production one-time-token handler, database token service, authentication filters, session cookies, and controllers are exercised end to end. Do not modify production auth behavior unless a new regression test exposes a defect.

**Tech Stack:** Java 25, Spring Boot 4, Spring Security WebAuthn/one-time-token filters, MockMvc, JUnit 5, PostgreSQL and Redis Testcontainers, Gradle.

## Global Constraints

- Use only synthetic test identities and isolated tenants; never use production credentials or customer data.
- Do not contact Resend or any external email/auth provider from tests.
- Password login remains enabled only for the single configured test account.
- Test role identity independently from scopes; `MANAGER` does not imply an API permission.
- Keep catalog callbacks disabled and do not change the production feature flags.
- Do not publish or deploy a test-only change. Release only if a verified production behavior defect requires a code fix.

---

## File Structure

- Modify `ford-ai-service/src/integrationTest/java/com/ford/service/identity/OperatorAuthenticationConfigurationIntegrationTest.java` — add the test-only email capture bean, synthetic identity fixture helpers, one-time-token lifecycle tests, session role/tenant assertions, password policy check, and representative scope/tenant assertions.
- Do not add a new integration class unless the existing class proves too unwieldy; keep this focused on the already configured authentication filter chains and test containers.
- Modify production auth classes only if a focused regression test proves a real defect: `OperatorAuthenticationConfiguration`, `IdentityCheckedOneTimeTokenService`, `OperatorUserDetailsService`, or the minimal affected authorization controller/service.

### Task 1: Exercise one-time-token request and redemption for all roles

**Files:**
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/identity/OperatorAuthenticationConfigurationIntegrationTest.java`
- Read-only reference: `ford-ai-service/src/main/java/com/ford/service/identity/adapter/email/ResendOneTimeTokenGenerationSuccessHandler.java`
- Read-only reference: `ford-ai-service/src/main/java/com/ford/service/identity/application/IdentityCheckedOneTimeTokenService.java`

**Interfaces:**
- Consumes: Spring bean `OneTimeTokenEmailSender.send(String recipient, String magicLink)`.
- Produces: a test-local recording sender exposing captured recipients and links to the integration test; no new production interface.

- [x] **Step 1: Add end-to-end OTT coverage and isolated test fixtures**

Seed one identity per supported role with a unique email and tenant. Request a token with CSRF and assert that the email sender captured the normalized address and one-time link. Extract the `token` query parameter, POST it to `/login/ott` with CSRF, assert a redirect and `SESSION` cookie, then request `/api/auth/me` with that session and assert the exact operator ID, role, and tenant. Repeat for `OPERATOR`, `MANAGER`, and `ADMIN`.

Add a test-only primary sender in the nested `TestBeans` configuration. The recording implementation should store `(recipient, magicLink)` pairs in a thread-safe collection and perform no network calls. Use fresh UUID-based emails/tenants per test and the existing Testcontainers database.

Define the identity fixture helper with this shape; insert the identity first, then its role, tenant, and each scope in their existing normalized tables, and return the same identity data:

```java
private com.ford.service.identity.api.OperatorIdentity createIdentity(
        String role, boolean enabled, java.util.Set<String> scopes) {
    var id = java.util.UUID.randomUUID();
    var tenant = java.util.UUID.randomUUID();
    var email = role.toLowerCase(java.util.Locale.ROOT) + "-" + id + "@example.test";
    jdbc.sql("insert into operator_identity (id, email_normalized, display_name, enabled) values (:id, :email, :name, :enabled)")
            .param("id", id).param("email", email).param("name", "Test " + role).param("enabled", enabled).update();
    jdbc.sql("insert into operator_identity_role (operator_id, role) values (:id, :role)")
            .param("id", id).param("role", role).update();
    jdbc.sql("insert into operator_identity_tenant (operator_id, tenant_id) values (:id, :tenant)")
            .param("id", id).param("tenant", tenant).update();
    for (var scope : scopes) {
        jdbc.sql("insert into operator_identity_scope (operator_id, scope) values (:id, :scope)")
                .param("id", id).param("scope", scope).update();
    }
    return new com.ford.service.identity.api.OperatorIdentity(
            id, email, "Test " + role, enabled, java.util.Set.of(role), java.util.Set.of(tenant), scopes);
}
```

The sender capture follows the existing `OneTimeTokenEmailSender` contract:

```java
private record Delivery(String recipient, String magicLink) {}

private static final class CapturingEmailSender implements OneTimeTokenEmailSender {
    private final java.util.List<Delivery> deliveries = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void send(String recipient, String magicLink) {
        deliveries.add(new Delivery(recipient, magicLink));
    }
}
```

Example test structure:

```java
@Test
void completesOneTimeTokenLoginForEverySupportedRole() throws Exception {
    for (var role : java.util.List.of("OPERATOR", "MANAGER", "ADMIN")) {
        var identity = createIdentity(role, true, role.equals("ADMIN")
                ? java.util.Set.of("profile", "email")
                : java.util.Set.of("operator:read", "profile", "email"));
        var session = requestAndRedeemOneTimeToken(identity.email());

        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value(identity.id().toString()))
                .andExpect(jsonPath("$.roles[0]").value(role))
                .andExpect(jsonPath("$.tenantIds[0]").value(identity.tenantIds().iterator().next().toString()));
    }
}
```

- [x] **Step 2: Pass the unit-test gate before integration verification**

From `ford-ai-service`, first run unit tests:

```bash
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
```

Expected: `BUILD SUCCESSFUL`, zero failed unit tests. Only then run the focused integration test:

```bash
./gradlew integrationTest --tests 'com.ford.service.identity.OperatorAuthenticationConfigurationIntegrationTest.completesOneTimeTokenLoginForEverySupportedRole'
```

Verification: unit suite passed before integration tests. The first integration compile exposed incorrect local helper return types (`MvcResult` versus `ResultActions`) and an inferred `Object` CSRF token; those test-only typing issues were corrected before the successful integration run.

- [x] **Step 3: Add only the test recorder, identity seeder, and OTT helper**

Add the primary test sender and `createIdentity(String, boolean, Set<String>)` helper to the test class. Add `requestAndRedeemOneTimeToken(String email)` that obtains `/api/auth/csrf`, posts the email to `/api/auth/one-time-token`, reads the captured link for that recipient, extracts only its `token` query parameter, posts it to `/login/ott` with the CSRF cookie/header, and returns the response `SESSION` cookie. Keep all token generation, persistence, redemption, and session work on the real configured Spring components. Do not print the token or full link.

- [x] **Step 4: Rerun the focused authentication integration class**

Run the exact focused command from Step 2.

Verification: all 14 class tests passed with zero skips/failures/errors, including all three identities' token redemption and role/tenant assertions. The test-only sender captured deliveries; no external email call occurred.

### Task 2: Verify neutral requests, configured password policy, and authorization boundaries

**Files:**
- Modify: `ford-ai-service/src/integrationTest/java/com/ford/service/identity/OperatorAuthenticationConfigurationIntegrationTest.java`
- Read-only reference: `ford-ai-service/src/main/java/com/ford/service/team/adapter/in/web/AdvisorController.java`
- Read-only reference: `ford-ai-service/src/main/java/com/ford/service/team/adapter/out/JdbcAdvisorRepository.java`
- Read-only reference: `ford-ai-service/src/main/java/com/ford/service/config/ApiExceptionHandler.java`

**Interfaces:**
- Consumes: Task 1 test-only identity seeder and recording email sender.
- Produces: regression assertions for the existing neutral OTT response, configured-password allowlist, explicit `ADMIN` route override, and tenant boundary.

- [x] **Step 1: Add neutral-response and password-policy assertions**

Compare status/body for a valid active identity, a disabled identity, and an unknown email on `POST /api/auth/one-time-token`; require `202` with `{"accepted":true}` in each case. Record the `one_time_tokens` row count before the requests and assert only the active identity adds a persisted token and captured delivery. For passwords, keep `operator@example.test` as the only configured account, seed it as `MANAGER`, and assert a successful session. Attempt the same configured password with active `OPERATOR` and `ADMIN` identities and assert the same generic `/login?error` redirect used for invalid credentials and no authenticated session.

- [x] **Step 2: Add scope/admin/tenant assertions**

Use `GET /api/v1/team/advisors?tenantId=<assigned>` after login. Assert `OPERATOR` and `MANAGER` succeed with the seeded `operator:read` scope; assert `ADMIN` succeeds without `operator:read` through the route's explicit `hasRole('ADMIN')` override. For every identity, assert a different unassigned tenant returns `403`. Do not infer additional permissions from the role names.

- [x] **Step 3: Pass the unit-test gate, then run the focused integration class**

```bash
./gradlew test
./gradlew integrationTest --tests 'com.ford.service.identity.OperatorAuthenticationConfigurationIntegrationTest'
```

Run from `ford-ai-service` in the displayed order.

Verification: `./gradlew test` passed before focused integration execution. The integration class passed 14 tests with zero skips/failures/errors; captured active/disabled/unknown response equality, configured-account-only password login, the Admin override, and 403 for an unassigned tenant all passed.

- [x] **Step 4: Determine whether a production fix is required**

Verification: every new behavior assertion passed against the current implementation; no production auth or authorization defect was demonstrated, so no production source change was made.

- [x] **Step 5: Confirm the focused integration class remains green**

```bash
./gradlew integrationTest --tests 'com.ford.service.identity.OperatorAuthenticationConfigurationIntegrationTest'
```

Verification: focused class passed with 14 tests, 0 skipped, 0 failures, and 0 errors; only the test-local sender handled email.

### Task 3: Run unit and full integration verification; determine release necessity

**Files:**
- Modify: `/Users/miguelangeldelgadillozarate/Development/Ford-scraping/tasks/todo.md` — record each completed action and its command/result using the repository-required per-checkbox format.
- Review: `ford-ai-service/src/integrationTest/java/com/ford/service/identity/OperatorAuthenticationConfigurationIntegrationTest.java` and any minimal production fix from Task 2.

**Interfaces:**
- Consumes: completed focused login matrix.
- Produces: verified test evidence and an evidence-based decision whether a protected production release is necessary.

Run Gradle commands from `/private/tmp/ford-uruapan-release/ford-ai-service`. Run Git inspection commands from `/private/tmp/ford-uruapan-release`.

- [x] **Step 1: Run the complete Service unit suite before integration verification**

```bash
./gradlew test --rerun-tasks --no-build-cache --max-workers=1
```

Verification: the exact forced command returned `BUILD SUCCESSFUL`; 5 tasks executed in 42 seconds.

- [x] **Step 2: Run the complete Service integration suite**

```bash
./gradlew integrationTest --rerun-tasks --no-build-cache --max-workers=1
```

Verification: `./gradlew integrationTest --rerun-tasks --no-build-cache --max-workers=1` returned `BUILD SUCCESSFUL`; 4 tasks executed in 1 minute 15 seconds. XML reports show 31 test suites, 81 tests total, 3 intentionally skipped snapshot tests because `FORD_CATALOG_PARITY_JDBC_URL` was absent, and 0 failures/errors. The login class itself had 14 tests, 0 skips.

- [x] **Step 3: Inspect changes and decide whether a production release applies**

```bash
git diff --check
git status --short
git diff -- ford-ai-service/src/integrationTest/java/com/ford/service/identity/OperatorAuthenticationConfigurationIntegrationTest.java
```

Verification: `git diff --check` passed. Only the auth integration test and the matrix spec/plan changed in this Service worktree; no production code changed. Existing production release `v0.1.25` remains current, so no test-only image was published or deployed.
