# Ford CRM Backend Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the CRM UI, API, and operator login under `https://ford.emme.business`, with Spring Security issuing passkey-first/email-OTP-fallback identities and Ford-web calling implemented Ford-service APIs.

**Architecture:** Cloudflare Pages serves static Ford-web assets on the existing hostname. Pages Functions forward same-origin API/auth paths to a server-side Ford-web BFF behind a new Tunnel origin hostname; the BFF keeps OAuth tokens in Redis and forwards requests to Ford-service on emme-1. Ford-service owns Spring Authorization Server, persistent WebAuthn identities, Spring Security one-time-token email recovery through Resend, and JWT-protected APIs.

**Tech Stack:** Spring Boot 4.1.0, Spring Security 7.1.0, Spring Authorization Server, Spring WebAuthn, PostgreSQL/Liquibase, existing Redis, Resend HTTPS API, React/Vite/TypeScript, Express, Cloudflare Pages Functions, Cloudflare Tunnel, Mise/Ansible, GHCR.

## Global Constraints

- Public UI, API, login, issuer, and passkey origin: `https://ford.emme.business`.
- API and auth traffic must use same-origin browser paths; do not add wildcard CORS.
- `ford.emme.business` currently routes through Cloudflare Tunnel to emme-1. Preserve a tested rollback route before changing it.
- Ford-service owns login and JWT issuance. Do not introduce an external identity provider.
- Passkeys are primary; email one-time codes are fallback/recovery only.
- Operator enrollment is invite-only. Never allow browser-selected roles or tenant IDs to become authorization claims.
- Store WebAuthn credentials, identity state, OAuth authorizations, OTP challenges, and BFF sessions durably; no in-memory production stores.
- Do not expose Resend keys, OAuth client credentials, JWT signing keys, Cloudflare credentials, or access tokens in frontend code, logs, test output, or repository files.
- Treat `ford-service/docs/openapi/frontend-api.yaml` as the canonical `/api/v1/**` contract. Ford-web may define its own BFF-only routes, but must not maintain an independently edited copy of the backend contract.
- Migrate mock-backed CRM behavior only where a corresponding Ford-service operation is implemented and authorized. No fake-success fallback in production.
- Run verification locally and on temporary hostnames before the `ford.emme.business` production route changes.

---

## Repository and file map

| Area | Main paths | Responsibility |
|---|---|---|
| Backend auth/API | `ford-ai-service/gradle/libs.versions.toml`, `src/main/java/com/ford/service/config/SecurityConfiguration.java`, new `identity` module, `src/main/resources/db/changelog/`, `docs/openapi/frontend-api.yaml` | Operator identities, WebAuthn, email OTP, OAuth/OIDC token issuance, JWT authorization, canonical API contract |
| Ford-web UI/BFF | `../Ford-web/src/pages/login/`, `src/widgets/test-lab/`, `src/store/useCrmStore.ts`, `server.ts`, `package.json`, new `functions/`, `wrangler.jsonc`, `_headers` | Login UI, BFF session boundary, typed backend client, Pages same-origin forwarding, CSP |
| Deployment | `deployment/ansible/roles/app/`, `ford-ai-service/.mise/tasks/deploy/`, `ford-ai-service/.mise/tasks/release/`, private deployment config | BFF image, Tunnel origin, Pages bindings, secret propagation, controlled cutover and rollback |

## Milestone order

1. Lock the contract and establish a temporary test origin.
2. Build and test backend identity and token issuance.
3. Build the Ford-web BFF and persistent server-side sessions.
4. Deploy Pages and origin routing on temporary hostnames; connect login and Test Lab.
5. Migrate CRM screens by implemented API family.
6. Cut over `ford.emme.business` only after end-to-end verification.

---

### Task 1: Reconcile OpenAPI and create a contract check

**Files:**
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`
- Modify: `ford-ai-service/src/test/java/com/ford/service/config/OpenApiContractTest.java`
- Modify: `../Ford-web/openapi.yaml`
- Create: `../Ford-web/openapi.bff.yaml`
- Modify: `../Ford-web/src/shared/api/openapi.yaml`
- Regenerate: `../Ford-web/src/shared/api/openapi.json`
- Modify: `../Ford-web/server.ts`
- Create: `../Ford-web/scripts/api-contract.ts`, `scripts/check-api-contract.ts`, and `scripts/sync-api-contract.ts`
- Modify: `../Ford-web/package.json`
- Test: `../Ford-web/tests/unit/apiContract.test.ts`

**Interfaces:**
- Backend AI request is `AiRespondRequest(conversationId?, tenantId?, messages[], containsSensitiveData?)`.
- Backend AI response is `AiRespondResponse(conversationId, replyText, suggestedTemperature, handoffSuggested, handoffReason, intent, leadStage, toolFamily, sourceDocumentIds)`.
- The BFF owns `/api/auth/*` and forwards the backend's canonical `/api/v1/**` routes.

- [x] **Step 1: Write failing drift checks.** Parse backend YAML and the Ford-web-served JSON; assert the AI operation and schemas match, the BFF contract contains only Ford-web routes, and all served formats have the same declared paths.
- [x] **Step 2: Run `cd ../Ford-web && bun run test -- --run tests/unit/apiContract.test.ts`.** Confirmed failure for stale JSON, different YAML copies, and the missing `/api/testlab/respond` contract.
- [x] **Step 3: Add typed BFF OpenAPI paths.** Added login start, callback, current session, logout, Test Lab, cookie session, and 4xx/5xx responses. Backend `/api/v1/**` definitions remain canonical in Ford-service.
- [x] **Step 4: Add sync/check scripts.** They require a Ford-service contract path, generate all Ford-web copies, and fail on route/component conflicts or drift.
- [x] **Step 5: Verify.** Focused contract tests pass; `bun run api:check -- ../Ford-uruapan/ford-ai-service/docs/openapi/frontend-api.yaml`, `bun run lint`, backend `OpenApiContractTest`, and `git diff --check` pass.
- [x] **Step 6: Commit only contract files.** Committed and pushed the backend contract/test as `ea7cee8` and Ford-web contract/generator/test as `ec89a9b`.

### Task 2: Add persistent operator identity and enrollment

**Files:**
- Modify: `ford-ai-service/gradle/libs.versions.toml`
- Create: `ford-ai-service/src/main/java/com/ford/service/identity/domain/OperatorIdentity.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/identity/application/OperatorIdentityService.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/identity/application/OperatorIdentityRepository.java`
- Create: `ford-ai-service/src/main/java/com/ford/service/identity/adapter/out/jdbc/JdbcOperatorIdentityRepository.java`
- Create: Liquibase change set under `ford-ai-service/src/main/resources/db/changelog/changes/`
- Modify: `ford-ai-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Test: identity unit tests and PostgreSQL integration tests under `ford-ai-service/src/test/java/` and `src/integrationTest/java/`

**Interfaces:**
- `OperatorIdentityService.findActiveByEmail(String email): Optional<OperatorIdentity>`.
- `OperatorIdentityService.acceptInvite(String inviteToken, String passkeyUserHandle): OperatorIdentity`.
- Identity contains stable subject, normalized email, enabled status, roles, tenant IDs, and enrollment state.

- [ ] **Step 1: Write unit tests for normalized email lookup, invite expiry, single-use invite acceptance, disabled accounts, role mapping, and tenant assignment.** Require invite acceptance to fail when the invitation is expired, already used, disabled, or invalid.
- [ ] **Step 2: Run `cd ford-ai-service && ./gradlew test --tests '*OperatorIdentityServiceTest' --no-daemon`.** Expect failures because the identity model and service do not exist.
- [ ] **Step 3: Add PostgreSQL tables and indexes.** Create operator identity, invitation, and tenant-membership records with uniqueness for normalized email and stable subject. Store a hash of invite tokens, never raw invitation secrets.
- [ ] **Step 4: Implement the repository with `JdbcClient`.** Keep SQL in the adapter. Map CRM roles only as `asesor → OPERATOR`, `gerente → MANAGER`, and `propietario → ADMIN`; reject unknown roles.
- [ ] **Step 5: Add integration tests.** Verify uniqueness, invite one-use behavior across transactions, disabled identity rejection, and membership persistence against PostgreSQL Testcontainers.
- [ ] **Step 6: Run focused tests and `./gradlew test --no-daemon`.** Expected: identity tests pass and existing tests remain unchanged.
- [ ] **Step 7: Commit identity and migration files only.** Use `git add` with the exact files above and commit `feat(identity): add invited operator registry`.

### Task 3: Persist Spring Security WebAuthn passkeys

**Files:**
- Modify: `ford-ai-service/gradle/libs.versions.toml`
- Modify: `ford-ai-service/build.gradle.kts`
- Create/modify: identity WebAuthn configuration under `ford-ai-service/src/main/java/com/ford/service/identity/`
- Modify: identity Liquibase change set and changelog master
- Test: passkey configuration, WebAuthn repository, and MockMvc security tests

**Interfaces:**
- Spring Security WebAuthn validates registration and authentication ceremonies.
- Production repositories bind credentials to the invited operator subject and persist credentials/challenges in PostgreSQL.

- [ ] **Step 1: Add configuration tests first.** Assert the configured RP ID is `ford.emme.business`, allowed origin is exactly `https://ford.emme.business`, and unknown origins are rejected.
- [ ] **Step 2: Run the focused tests and confirm failure.** Run `cd ford-ai-service && ./gradlew test --tests '*WebAuthn*' --no-daemon`.
- [ ] **Step 3: Verify Spring Boot 4.1.0 manages the WebAuthn dependency.** Add the BOM-managed `spring-security-webauthn` dependency without an explicit version and inspect it with `./gradlew dependencyInsight --dependency spring-security-webauthn --configuration runtimeClasspath --no-daemon`.
- [ ] **Step 4: Configure Spring's supported WebAuthn DSL and JDBC repositories.** Use the framework WebAuthn credential repositories, not in-memory demo implementations. Restrict registration to authenticated/invited operators.
- [ ] **Step 5: Add tests for registration, assertion success, bad challenge, replayed challenge, unknown credential, disabled operator, and wrong origin.** Use generated test assertions or Spring Security test support; never include real credentials.
- [ ] **Step 6: Run focused WebAuthn tests and `./gradlew test --no-daemon`.** Expected: persisted credentials authenticate only their enrolled, enabled operator.
- [ ] **Step 7: Commit WebAuthn files only.** Commit `feat(identity): add persistent passkey authentication`.

### Task 4: Add Spring one-time-token email fallback through Resend

**Files:**
- Configure: Spring Security `oneTimeTokenLogin()` and JDBC `OneTimeTokenService`
- Create: `ford-ai-service/src/main/java/com/ford/service/identity/adapter/out/email/ResendOneTimeTokenHandler.java`
- Create: request-throttling policy and JDBC-backed rate-limit adapter under `identity/`
- Create: `ResendEmailSender` adapter under `identity/adapter/out/email/`
- Modify: identity Liquibase change set and changelog master
- Modify: `ford-ai-service/src/main/resources/application.yml`
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`
- Modify: Ansible app environment template and private deployment config key allowlist
- Test: OTP policy unit tests, Resend adapter HTTP tests, and JDBC integration tests

**Interfaces:**
- Spring Security generates, persists, consumes, and expires one-time tokens through its `OneTimeTokenService`.
- `EmailSender.sendLoginLink(String destination, URI magicLink, Instant expiresAt): void`.

- [ ] **Step 1: Write OTT policy tests.** Cover active-operator-only delivery, neutral response for unknown email, expiry, one-use consumption, request throttling, and no token/email leakage in logs.
- [ ] **Step 2: Run focused identity tests.** Confirm the new tests fail before implementation.
- [ ] **Step 3: Use Spring Security's JDBC `OneTimeTokenService`.** Include its supported schema through Liquibase or an equivalent versioned migration, and configure a short token lifetime.
- [ ] **Step 4: Implement the success handler and Resend adapter.** Build a same-origin magic link from trusted external-origin config; send through Resend HTTPS API; load API key and verified sender from runtime secrets only.
- [ ] **Step 5: Add fake-sender and HTTP contract tests.** Verify Resend 2xx, 4xx, timeout, retryable 5xx, and that failures do not disclose whether the email belongs to an operator.
- [ ] **Step 6: Add integration tests for expiry, replay, concurrent consumption, and throttling.** Two simultaneous verifications must not both succeed.
- [ ] **Step 7: Run focused auth tests and `./gradlew test --no-daemon`.** Expected: OTT recovery is one-time, bounded, non-enumerating, and failure-safe.
- [ ] **Step 8: Commit OTT, email, config-template, docs, and test files only.** Commit `feat(identity): add Resend one-time-token recovery`.

### Task 5: Issue signed OIDC/JWT tokens and protect the API

**Files:**
- Modify: `ford-ai-service/gradle/libs.versions.toml` and `build.gradle.kts`
- Modify: `ford-ai-service/src/main/java/com/ford/service/config/SecurityConfiguration.java`
- Create: authorization-server configuration and identity claim mapper under `ford-ai-service/src/main/java/com/ford/service/identity/`
- Modify: `ford-ai-service/src/main/resources/application.yml` and `application-prod.yml`
- Modify: identity Liquibase changes for registered client and authorization persistence
- Modify: `ford-ai-service/docs/openapi/frontend-api.yaml`, `docs/openapi/security.md`, `docs/operations.md`
- Modify: deployment secret adoption, environment template, diagnostics, and config tests
- Test: security-chain, token-claim, issuer/JWKS, and protected-API tests

**Interfaces:**
- Public issuer: `https://ford.emme.business`.
- BFF OAuth client uses Authorization Code with PKCE, exact redirect URI, and a server-side client credential.
- API JWT claims: `iss`, `aud`, `sub`, `roles`, `tenant_ids`, `scope`, `iat`, `nbf`, `exp`.

- [ ] **Step 1: Write security tests.** Verify unauthenticated API denial, invalid signature/issuer/audience denial, expired-token denial, role/scope/tenant enforcement, and permitted health/webhook paths.
- [ ] **Step 2: Add authorization-server dependency through the Spring Boot 4.1.0 BOM.** Confirm resolved versions with Gradle `dependencyInsight`; do not copy a version from a newer documentation release.
- [ ] **Step 3: Add a persistent registered-client and authorization store.** Register only the Ford-web BFF client with Authorization Code, PKCE, exact callback URI, minimal scopes, and no implicit/password grants.
- [ ] **Step 4: Configure separate filter chains.** The authorization endpoints support login/session/CSRF requirements; `/api/v1/**` remains stateless and bearer-JWT protected. Permit only documented public health and signed webhook endpoints.
- [ ] **Step 5: Add token signing and JWK publication.** Load a persistent private signing key from protected runtime configuration, publish its public key through JWKS, and support key identifier rotation without invalidating active tokens immediately.
- [ ] **Step 6: Map only backend registry data into JWT claims.** Derive subject, role, tenant IDs, and scopes from the enabled operator record. Ignore any browser-supplied role or tenant override.
- [ ] **Step 7: Enable production JWT validation and issuer/audience settings through Ansible.** Keep local development behavior explicit; fail startup when production auth is enabled without issuer, audience, or signing material.
- [ ] **Step 8: Update canonical OpenAPI.** Document issuer discovery, authorization/token/JWKS endpoints, passkey login/registration, OTP fallback, required scopes, and 401/403 behavior using the real `https://ford.emme.business` issuer.
- [ ] **Step 9: Run security tests, `./gradlew test --no-daemon`, and `./gradlew build --no-daemon`.** Expected: the same signed JWT accepted by the resource server and rejected after issuer/audience/signature/claim changes.
- [ ] **Step 10: Commit backend auth and deployment-config changes only.** Commit `feat(security): issue operator tokens with Spring Security`.

### Task 6: Build the Ford-web BFF with persistent sessions

**Files:**
- Modify: `../Ford-web/package.json` and `bun.lock`
- Modify: `../Ford-web/server.ts`
- Create: `../Ford-web/src/server/auth/oidcClient.ts`
- Create: `../Ford-web/src/server/auth/sessionStore.ts`
- Create: `../Ford-web/src/server/fordServiceClient.ts`
- Create: `../Ford-web/Dockerfile.bff`
- Modify: Ford-web environment example and BFF OpenAPI contract
- Modify: deployment Compose, app environment template, app tasks, and BFF image release/deploy task
- Test: Express BFF unit/HTTP tests and Redis-backed session integration tests

**Interfaces:**
- `FordServiceClient.request(path, accessToken, init): Promise<Response>` sends requests only to the configured internal Ford-service origin.
- `BffSessionStore.create(authState): Promise<string>`, `get(sessionId)`, `rotate(sessionId, session)`, and `delete(sessionId)` use Redis with TTL.
- Browser receives only a random opaque session cookie; tokens remain in Redis.

- [ ] **Step 1: Write failing BFF route tests.** Cover login redirect parameters, state/PKCE/nonce verification, callback rejection, session rotation, 401 expiry, logout, CSRF checks, and absent-token denial.
- [ ] **Step 2: Run `cd ../Ford-web && bun run test -- --run tests/unit/bffAuth.test.ts`.** Expect failures before BFF modules exist.
- [ ] **Step 3: Add Node OIDC client and Redis session dependencies.** Pin compatible versions in Bun lockfile and keep all provider secrets server-only.
- [ ] **Step 4: Implement authorization-code flow.** Store state, nonce, and verifier server-side; check state on callback; exchange the code server-to-server; store access/refresh tokens in Redis; rotate session identifier; use `Secure`, `HttpOnly`, `SameSite=Lax`, narrow-path cookies.
- [ ] **Step 5: Implement BFF routes.** Add login, callback, current-session, logout, and allowlisted `/api/v1/**` proxy routes. Forward no arbitrary URL or hop-by-hop headers. Apply CSRF checks to cookie-authenticated mutations and bounded request timeouts/body sizes.
- [ ] **Step 6: Add production BFF mode.** It serves only health, auth, and allowlisted backend proxy routes; it must not mount demo CRM mocks, Gemini/local fallback, Vite, or static admin routes.
- [ ] **Step 7: Add Redis restart tests and production image.** Use Redis Testcontainers for session continuity and owner isolation. Build a non-root minimal runtime image from `Dockerfile.bff`.
- [ ] **Step 8: Add BFF deployment.** Add a separate BFF image parameter to the emme-1 Compose deployment, internal-only bind port, Redis connection, OAuth client settings, and health check. Keep secrets in Ansible's mode-0600 app environment.
- [ ] **Step 9: Run `bun run lint`, `bun run test -- --run`, and `bun run build` in Ford-web; run backend unit/integration tests for auth session contracts.** Expected: session survives BFF restart, browser never receives tokens, and production routes cannot reach mock endpoints.
- [ ] **Step 10: Commit only Ford-web BFF, image, deployment, and tests.** Commit `feat(bff): add persistent Ford-service login sessions`.

### Task 7: Add Pages Functions, same-origin paths, and temporary origin routing

**Files:**
- Create: `../Ford-web/functions/[[path]].ts` (or namespace-specific route handlers sharing one proxy module)
- Create: `../Ford-web/public/_routes.json` to limit Function invocations to dynamic routes
- Create: `../Ford-web/wrangler.jsonc`
- Create: `../Ford-web/public/_headers`
- Create: `../Ford-web/.pagesignore` if required by the chosen Pages build layout
- Create: Pages Function route tests
- Modify: `deployment/ansible/roles/app/templates/compose.yml.j2` and app health checks for internal BFF only
- Modify: Cloudflare Tunnel route configuration through the existing managed secret/config path
- Modify: deployment and Cloudflare runbooks

**Interfaces:**
- Public origin remains `https://ford.emme.business`.
- Pages Function receives `FORD_ORIGIN_URL` as a server-only binding and proxies only `/api/*`, `/oauth2/*`, `/.well-known/*`, `/login/*`, and `/webauthn/*` to a temporary backend origin.
- Static routes are served from Pages; backend origin routes never resolve back through the Pages custom domain.

- [ ] **Step 1: Write route tests.** Assert each allowed path maps to the origin, query/body/authorization/cookies are preserved as intended, `Host`/forwarded headers are sanitized, redirects retain the public origin, and unknown paths do not proxy.
- [ ] **Step 2: Configure Pages Function routing.** Use `_routes.json` to invoke Functions only for `/api/*`, `/oauth2/*`, `/.well-known/*`, `/login/*`, and `/webauthn/*`; leave static asset requests on the Pages static path. Use the catch-all handler only behind that route filter. Test both included and excluded paths.
- [ ] **Step 3: Implement Pages Function proxy.** Enforce the same explicit path allowlist in code; strip spoofed `Forwarded` and `X-Forwarded-*` headers; set trusted public host/proto metadata; disable caching for auth/API responses; reject redirects that would expose the temporary origin.
- [ ] **Step 4: Add CSP and security headers.** Set a restrictive `default-src`, `script-src`, `connect-src 'self'`, `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'`, HSTS, and nosniff. Enumerate only required image/font sources used by the UI; do not add wildcard origins.
- [ ] **Step 5: Add temporary hostnames.** Provision a Pages preview/custom test hostname and a distinct Tunnel origin hostname to the emme-1 BFF/reverse proxy. Keep `ford.emme.business` on its current route.
- [ ] **Step 6: Verify origin separation.** Test static requests, health, issuer discovery, auth redirects, BFF callback, and API proxy through the temporary hostname. Assert no request loops and no direct token leak.
- [ ] **Step 7: Verify browser same-origin behavior.** Run Playwright against the temporary hostname and assert frontend calls use relative `/api/...` URLs and receive no CORS errors. Confirm CSP reports no blocked required assets.
- [ ] **Step 8: Commit Pages Functions, headers, config, and tests only.** Commit `feat(pages): add same-origin CRM routing`.

### Task 8: Replace demo login and connect Test Lab

**Files:**
- Modify: `../Ford-web/src/pages/login/ui/LoginPage.tsx`
- Modify: `../Ford-web/src/store/useCrmStore.ts`
- Modify: `../Ford-web/src/App.tsx`
- Modify: `../Ford-web/src/widgets/test-lab/ui/TestLabWidget.tsx`
- Modify: `../Ford-web/src/shared/api/openapi.yaml` and generated JSON
- Test: login, OTP fallback, session expiry, logout, Test Lab multi-turn component/E2E tests

**Interfaces:**
- UI auth calls only `/api/auth/login`, `/api/auth/me`, and `/api/auth/logout` on the same origin.
- Test Lab sends `{conversationId, tenantId?, messages:[{role:"user",content}], containsSensitiveData?}` to `/api/v1/ai/respond` through the BFF.
- Auth UI provides passkey sign-in as the primary action and email OTP fallback as a recovery action.

- [ ] **Step 1: Add failing UI tests.** Verify the demo role selector/password are absent, passkey action invokes the same-origin login route, OTP fallback has neutral copy, session restore uses `/api/auth/me`, logout clears local UI state, and auth errors are visible.
- [ ] **Step 2: Change Test Lab tests to assert the canonical contract.** Verify custom turns keep the conversation ID and presets remain isolated; no Gemini/local success fallback runs after errors.
- [ ] **Step 3: Replace mock login in the UI.** Remove local demo login/token behavior for production mode. Store only non-sensitive operator profile UI data, never JWTs.
- [ ] **Step 4: Connect the passkey and OTP flows.** Call the WebAuthn challenge/assertion endpoints, encode/decode binary WebAuthn fields as required by the API contract, and send OTP only after the fallback action is selected.
- [ ] **Step 5: Connect Test Lab through authenticated BFF session.** Send typed request fields; remove manual Authorization token input; keep visible 401/403/5xx failures and stable conversation IDs.
- [ ] **Step 6: Run Ford-web lint, unit tests, build, and Playwright auth/Test Lab tests against temporary origin.** Expected: passkey login, email fallback, logout, and multi-turn Test Lab work without exposing tokens.
- [ ] **Step 7: Commit UI auth and Test Lab changes only.** Commit `feat(ui): use backend passkey auth and Test Lab API`.

### Task 9: Migrate CRM mock endpoints by API family

**Files:**
- Create: typed `../Ford-web/src/shared/api/fordServiceApi.ts`
- Modify: `../Ford-web/src/store/useCrmStore.ts`
- Modify: `../Ford-web/src/shared/lib/useSseEvents.ts`
- Modify: `../Ford-web/src/widgets/service-status/ui/ServiceStatusWidget.tsx`
- Modify: feature widgets that consume conversations, messages, handoffs, leads, metrics, vehicle inventory, finance, appointments, advisors, and bot configuration
- Modify: `../Ford-web/server.ts` to disable/remove mock routes from production mode
- Modify: OpenAPI contract and tests for each API family

**Interfaces:**
- Typed API client uses relative BFF paths and the browser session cookie.
- Each store action has explicit loading/error/result behavior and updates state only from validated backend responses.

- [ ] **Step 1: Build the endpoint-to-screen matrix from actual UI call sites and OpenAPI `x-implementation-status`.** Include request/response types, scope, tenant requirements, real backend controller, and screen owner. Exclude operations marked planned.
- [ ] **Step 2: Add failing API-client tests for URL construction, schema decoding, 401/403 handling, ProblemDetail mapping, pagination, and no mock fallback.** Use MSW fixtures from the canonical schemas.
- [ ] **Step 3: Implement the typed same-origin API client.** Do not create endpoint-specific clients that duplicate auth/error logic.
- [ ] **Step 4: Migrate conversations/messages and handoffs.** Make `fetchConversations` parse and apply returned page data; connect SSE to same-origin `/api/v1/operator/events/stream` through the BFF; retain tenant checks on the service.
- [ ] **Step 5: Migrate leads, metrics, inventory, finance, appointments, advisors, and bot configuration one family at a time.** For each family, add request/response tests before wiring the store/widget and remove its production mock route.
- [ ] **Step 6: Keep unsupported UI actions disabled with explicit unavailable states.** Never return mock `200` success in production for planned/unimplemented backend operations.
- [ ] **Step 7: Run component, API-client, and end-to-end tests per family, then `bun run lint`, `bun run test -- --run`, and `bun run build`.** Expected: production screen data comes from authenticated backend responses.
- [ ] **Step 8: Commit each independently working API family.** Use focused commits such as `feat(crm): connect conversations and handoffs` and `feat(crm): connect leads and inventory`.

### Task 10: Cut over `ford.emme.business` safely

**Files:**
- Modify: Cloudflare Pages custom-domain and Function bindings using the configured Cloudflare account integration
- Modify: existing Cloudflare Tunnel published routes through the established configuration mechanism
- Modify: `ford-ai-service/.mise/tasks/deploy/app` only if needed to pass the pinned BFF image
- Modify: deployment/runbook docs
- Test: smoke/e2e checks and rollback script/record

**Interfaces:**
- Pages serves the public root.
- Same-origin paths `/api/**`, `/oauth2/**`, `/.well-known/**`, `/login/**`, and `/webauthn/**` route to the emme-1 origin.
- `ford-origin.emme.business` is never exposed as a browser API base URL.

- [ ] **Step 1: Run deployment preflight.** Confirm the pinned Pages build, BFF image digest, backend image digest, Redis/PostgreSQL health, issuer/JWKS, Resend sender-domain readiness, and rollback mapping. Print presence/readiness only, never secret values.
- [ ] **Step 2: Deploy backend and BFF to emme-1 using the existing GHCR/Ansible path.** Preserve the previous backend/BFF image references for rollback and verify emme-2 data services are unchanged.
- [ ] **Step 3: Deploy Pages to the temporary hostname and run production-like auth/API smoke tests.** Verify passkey origin, email delivery with a designated test operator, scope/tenant restrictions, session persistence after BFF restart, and Test Lab persisted follow-up.
- [ ] **Step 4: Capture current Tunnel/DNS values and validate rollback commands before cutover.** Do not change `ford.emme.business` if any temporary-origin test fails.
- [ ] **Step 5: Attach `ford.emme.business` to Pages and move the Tunnel to the separate origin hostname.** Apply changes as a coordinated cutover; keep the previous route details immediately available.
- [ ] **Step 6: Verify public flows.** Check `/`, health, OpenAPI, login redirect, passkey login, OTP fallback, authenticated API, Test Lab second turn after refresh, logout, and unauthorized requests. Check CSP and absence of browser CORS failures.
- [ ] **Step 7: Roll back immediately if public login/API or service health fails.** Restore the saved `ford.emme.business` Tunnel route and prior image digests; do not reset PostgreSQL or Redis.
- [ ] **Step 8: Record observed deployment state.** Update operations docs with final host mappings, Pages project/branch, BFF/backend digests, auth variables by name (never value), test results, and rollback steps.

## Acceptance criteria

- CRM, API, and auth paths are browser-facing only at `https://ford.emme.business`.
- Passkeys are the primary login; Resend OTP is fallback/recovery and resists replay, brute force, and account enumeration.
- Spring Security issues and validates operator JWTs with backend-controlled role, scope, and tenant claims.
- Browser uses secure same-origin cookies; no access token, refresh token, signing key, or client secret is stored in local storage or shipped in the Pages bundle.
- Test Lab returns real Ford-service responses and persisted multi-turn context; failures remain visible and never become mock answers.
- OpenAPI source, served YAML/JSON, BFF routes, and generated TypeScript contract checks agree.
- CRM screen families use real implemented Ford-service operations or show explicit unavailable behavior.
- Browser calls need no CORS wildcard; CSP is restrictive and explicitly configured.
- Production hostname cutover is preceded by temporary-origin verification and has a tested rollback path.

## Execution constraints

- Do not deploy or change Cloudflare DNS/Tunnel/Pages until the user approves the concrete preflight and cutover plan.
- Do not contact real operators or send OTPs to production addresses during tests; use test identities and a fake sender except in the explicitly approved email smoke test.
- Do not commit unrelated pre-existing dirty files in either repository.
