# Ford Service Login Role Matrix — Design

## Goal

Verify role-to-session mapping through the shared login paths, scoped API and tenant enforcement, and neutral invalid-login behavior. Use synthetic test identities only.

## Current contract

- Roles: `OPERATOR` (Asesor), `MANAGER` (Gerente), and `ADMIN` (Propietario). CRM aliases normalize `asesor`/`operator`, `gerente`/`manager`, and `propietario`/`admin`.
- Sign-in methods: passkey is primary; email one-time link is available to active identities; password login is enabled only for one configured account.
- Roles and API permissions are separate: API access is primarily scope-based, with explicit `ADMIN` overrides on selected controllers. Tests must verify seeded scopes and tenant membership rather than assume `MANAGER` automatically grants a permission.
- Production already runs Ford Service `v0.1.25`. No login symptom was reported, so this work starts as test coverage; a new production image is required only if the matrix finds a code defect.

## Recommended test scope

1. In the PostgreSQL integration suite, seed one active identity for each role, each with an isolated tenant and the scopes needed for the scenario.
2. Exercise the email one-time-token path through token request, test-only email capture, token redemption, session creation, and `/api/auth/me`. Assert exact role and tenant for all three identities without contacting Resend.
3. Exercise the password flow only for the configured login account. Verify a different active role identity cannot use that password path; keep error messages generic.
4. Verify disabled and unknown identities receive the same neutral token-request response and cannot create an authenticated session; confirm only the active identity's normalized username is persisted with the generated token.
5. Check permission boundaries on `GET /api/v1/team/advisors` using each identity's seeded scopes and tenant membership: OPERATOR/MANAGER receive the explicit `operator:read` scope; a MANAGER without that scope is denied; ADMIN exercises the route's `hasRole('ADMIN')` override without that scope. All three must be denied access to an unassigned tenant.
6. Retain the existing WebAuthn client unit coverage. Do not claim a full browser/WebAuthn ceremony test unless a stable synthetic authenticator fixture exists; add one only if existing Spring test support can drive it without real credentials or external services.

## Non-goals

- No production user credentials, customer data, or email-provider calls in tests.
- No password-login enablement for all roles; production policy intentionally limits password access to the configured account.
- No production behavior change if tests pass. Do not create a no-op image release merely to repeat v0.1.25.

## Acceptance

- All three roles complete the synthetic one-time-token login flow and `/api/auth/me` returns their expected role and tenant.
- Password login succeeds only for the configured account; invalid, inactive, and unregistered identities do not reveal account existence.
- Representative session authorization follows seeded scopes and tenant assignments, including the `ADMIN` route override; no role-to-scope mapping is inferred.
- Focused and full Service tests pass. If a defect is found, write a failing regression test first, fix the smallest behavior, run the suite, then release through the protected Service workflow.
