# BFF Consolidation Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` to implement this plan task by task. Keep the BFF deployed until the final cutover checks pass.

**Goal:** Serve the Ford CRM UI and browser auth through Ford-service, keep Redis-backed Spring sessions and bearer JWT clients, and remove the separate BFF workload while retaining the OpenTelemetry Collector.

**Architecture:** Spring Security remains the only browser authenticator. Spring Session stores its opaque session in Redis. Session-backed API authorization refreshes the operator identity from the database; bearer JWT requests remain stateless and keep current claims/tenant behavior. The browser calls same-origin Spring routes; Spring continues to fetch the static React app from Pages.

**Tech Stack:** Spring Boot 4.1 / Spring Security 7, Spring Session Redis, Java 25, Ford-web React/Vite, Vitest, Gradle.

## Global Constraints

- Preserve the existing external bearer JWT contract and OAuth issuer.
- Require CSRF for unsafe browser-session requests; bearer requests do not use CSRF cookies.
- Never authorize from caller-supplied tenant/role headers.
- Keep the OpenTelemetry Collector and existing Pages UI upstream.
- Keep BFF/Tunnel/DNS rollback paths until cutover checks succeed.
- Never stage deployment secrets, `.tgrep/`, or unrelated user edits.

## Files and Responsibilities

- `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`: session/JWT API-chain composition and CSRF policy.
- `ford-ai-service/src/main/java/com/ford/service/identity/adapter/in/web/OperatorAuthenticationConfiguration.java`: Spring login/logout and auth-chain routes.
- `ford-ai-service/src/main/java/com/ford/service/identity/adapter/in/web/OperatorSessionController.java`: authenticated session profile endpoint.
- `ford-ai-service/src/main/java/com/ford/service/identity/application/OperatorUserDetailsService.java`: serializable identity principal for Spring sessions.
- `ford-ai-service/src/main/java/com/ford/service/identity/adapter/in/web/OperatorSessionRefreshFilter.java`: refresh session principal from active DB identity on API calls.
- `ford-ai-service/src/main/java/com/ford/service/operator/application/AuthenticatedOperator.java`: normalize JWT and refreshed session identities.
- `ford-ai-service/src/main/resources/application.yml`: Redis-backed Spring Session timeout.
- `ford-ai-service/src/test/java/com/ford/service/operator/OperatorAuthorizationTest.java`: session principal normalization tests.
- `ford-ai-service/src/integrationTest/java/com/ford/service/identity/OperatorAuthenticationConfigurationIntegrationTest.java`: login, CSRF, session API, DB identity refresh, logout, and bearer JWT regression tests.
- `Ford-web/src/pages/login/passkeyLogin.ts`: return to CRM after Spring establishes the session.
- `Ford-web/src/shared/api/operatorAuth.ts`: same-origin Spring profile/logout/CSRF calls.
- `Ford-web/src/server/auth/createBffApp.ts`, `src/server/auth/*`, `src/server/bffMain.ts`, `src/server/bffProxy.ts`, `Dockerfile.bff`, `dist/bff.mjs`, and `package.json`: BFF implementation/build artifacts, retained until production cutover is verified.
- `deployment/ansible/roles/app/templates/compose.yml.j2` and `deployment/runtime/app/compose.yml`: BFF container definition, removed only in deployment phase.
- `deployment/config/oci.env.example`, `deployment/runtime/app/.env.template`, workflows, and operations docs: BFF image/env and tunnel routing contract.

## Task 1: Spring session principal and API authorization

1. Add an integration test that logs in through `/login`, keeps the returned Spring session cookie, reads `/api/auth/me`, reads `/api/v1/operator/me`, then logs out with CSRF and verifies the session no longer authenticates.
2. Add a test showing a bearer JWT request still succeeds without a session cookie or CSRF token and does not create a browser session.
3. Add a test that disables an operator in the DB after login and verifies the next session API request is denied; this proves roles/tenants are refreshed from the DB.
4. Run the focused integration test and confirm the new assertions fail because API security is stateless and `/api/auth/me` is missing.
5. Implement a serializable Spring principal, API-request identity refresh, session-aware API chain, conditional CSRF exemption only for bearer Authorization headers, and `/api/auth/me` plus `/api/auth/logout` endpoints using existing Spring Security handlers.
6. Verify the focused tests pass, then run the operator identity/security unit tests.

## Task 2: Ford-web same-origin Spring auth

1. Add tests for passkey success returning `/` after Spring authentication, session profile retrieval, CSRF initialization, and POST logout.
2. Verify tests fail against the old BFF callback assumption.
3. Update UI auth calls to same-origin Spring endpoints; remove the passkey redirect to `/api/auth/login` after successful Spring passkey auth.
4. Run `npm test -- --run src/shared/api src/pages/login` and `npm run lint`.
5. Build the UI and verify generated asset paths and redirects remain same-origin.

## Task 3: Deployment and cutover preparation

1. Add operations-contract checks asserting the compose config retains Ford-service and the existing Collector and does not remove BFF before the public cutover gate.
2. Document the two-stage release: deploy compatible Spring/UI first, then switch `ford.emme.business` Tunnel/DNS, verify UI/auth/API/webhook, then remove BFF and `ford-origin` routes.
3. Verify the live Pages UI upstream is still reachable through Spring and that `/webhooks/meta/**` cannot fall through to the SPA.
4. Deploy the compatible image while retaining the old image and BFF; verify password, passkey, OTT, session resume, logout, CSRF, API reads/writes, SSE, and bearer JWT clients.
5. Change tunnel and DNS only after the auth checks pass. Current Cloudflare token lacks DNS Edit, so DNS cutover is blocked until that permission exists.
6. Remove BFF compose/image/env and `ford-origin` routes only after public verification. Keep the OpenTelemetry Collector.

## Verification

- Backend: `./gradlew test integrationTest --no-daemon --no-configuration-cache --max-workers=1`.
- UI: `npm test -- --run`, `npm run lint`, `npm run build`, and `npm run pages:check`.
- Deployment contract: run the repository's current operations contract and `mise run deploy:check` before any deployment.
- Public cutover: verify UI assets, `/api/auth/me`, login methods, representative protected API, webhook challenge, and existing bearer JWT access via `ford.emme.business`.

## Acceptance Criteria

- Browser auth lives in Spring Security and Spring Session Redis; no browser request needs the BFF.
- Session users and bearer JWT users can access intended CRM API routes with tenant authorization.
- Unsafe browser requests require CSRF; bearer clients continue without CSRF tokens.
- Login state survives Ford-service restarts backed by Redis and logout invalidates it.
- Ford-service remains the UI/API origin and fetches UI assets from Pages.
- Final emme-1 application/telemetry workloads are Ford-service plus the existing OpenTelemetry Collector.
- No BFF or `ford-origin` dependency remains after the verified public cutover.
