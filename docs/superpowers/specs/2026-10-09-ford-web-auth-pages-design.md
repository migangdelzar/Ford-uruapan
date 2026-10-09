# Ford CRM Pages, backend authentication, and API integration

**Status:** Proposed design for user review
**Date:** 2026-10-09

## Goal

Connect the Ford CRM UI to Ford-service on emme-1, use Spring Security as the operator identity and token authority, and publish the CRM UI, API, and authentication paths under the existing `https://ford.emme.business` hostname.

## Current state

- `ford.emme.business` currently routes through Cloudflare Tunnel to the Ford-service app on emme-1. The public `/api/v1/health` endpoint returned HTTP 200 during the architecture review.
- Ford-service currently configures Spring Security as a stateless JWT Resource Server. Its JWT validation defaults to disabled unless deployment configuration enables it. No Spring Authorization Server, passkey login, OTP flow, or Resend adapter exists in the source.
- Ford-web login is a local demo flow. The Test Lab UI calls Ford-web's `/api/testlab/respond` without an access token. Ford-web's Express proxy rejects requests without a bearer token and has no real token source.
- Most CRM routes in Ford-web's Express server return local mock data. The UI is not yet broadly backed by Ford-service.
- OpenAPI has drifted: Ford-web's root and nested YAML differ, the served JSON contains only 17 paths, and the Test Lab proxy route is absent. Ford-service's `docs/openapi/frontend-api.yaml` is the best starting point for the canonical backend contract.
- The current backend security dependency resolves Spring Security 7.1.0 under Spring Boot 4.1.0. `AuthenticatedOperator` reads `sub`, roles, and tenant IDs from validated JWT claims. There is no complete operator authentication/account provisioning flow.

## Decisions

1. **Keep the public hostname.** `https://ford.emme.business` remains the only browser-facing hostname for the UI, API, and login/authentication routes.
2. **Host the static CRM on Cloudflare Pages.** Pages owns the custom domain after a tested migration from the existing Tunnel route.
3. **Keep Spring Security as identity owner.** Ford-service will add OAuth2 Authorization Server support and issue the JWTs its API validates. No separate external identity provider is introduced.
4. **Use passkeys as the primary sign-in method.** Email one-time codes are a fallback/recovery method delivered through Resend.
5. **Use a same-origin server-side BFF.** Pages Functions route browser paths to a Ford-web BFF and Ford-service. The BFF keeps tokens server-side and uses a secure HttpOnly cookie for the browser session. The BFF session store uses the existing Redis service on emme-2, not browser local storage.
6. **Use a separate Tunnel origin hostname for Cloudflare-to-emme traffic.** For example, `ford-origin.emme.business` can route to a local emme-1 reverse proxy. It is infrastructure plumbing; browser requests and generated links continue to use `ford.emme.business`.
7. **Reconcile the API contract before broad UI wiring.** Ford-service's frontend OpenAPI YAML becomes the canonical backend contract. Ford-web may keep a separate contract for its BFF-only endpoints, but it must not copy and edit the backend contract independently. Generated or validated TypeScript types must derive from the canonical contract.
8. **Integrate incrementally.** First deliver login and Test Lab end to end, then migrate CRM areas against implemented backend operations. Mock routes remain clearly marked development-only until replaced.

## Target request layout

```text
Browser: https://ford.emme.business
  / and static assets       -> Cloudflare Pages
  /api/auth/*               -> Pages Function -> Ford-web BFF -> Ford-service auth/token APIs
  /api/v1/*                 -> Pages Function -> Ford-web BFF -> Ford-service API
  /oauth2/*, /.well-known/* -> Pages Function -> Ford-service on emme-1
  /webauthn/*, /login/*     -> Pages Function -> Ford-service on emme-1

Pages Function -> ford-origin.emme.business -> Cloudflare Tunnel -> emme-1 local reverse proxy
                                                                  -> Ford-web BFF
                                                                  -> Ford-service
Ford-service -> existing PostgreSQL/Redis services on emme-2
```

The public issuer is `https://ford.emme.business`. The origin route must not point back to that public Pages hostname, which would create a proxy loop. The emme-1 reverse proxy and Spring forwarded-header handling must trust only the Cloudflare-to-origin path and preserve the external issuer and browser origin. WebAuthn configuration must validate the exact public origin and use the `ford.emme.business` relying-party ID.

The current `ford.emme.business` Tunnel route must be replaced only after the Pages custom domain, origin hostname, path routing, health checks, and rollback are verified on temporary hostnames. The old route/configuration must be preserved for rollback until production verification passes.

## Authentication flow

1. The browser starts login at the BFF under `/api/auth/login`. The BFF creates single-use OAuth state and PKCE data in Redis and redirects the browser to Ford-service's authorization endpoint under `ford.emme.business`.
2. Ford-service authenticates the allowlisted operator with a registered passkey. Its WebAuthn credential records are persisted in PostgreSQL; in-memory demo repositories are not used in production.
3. If the operator cannot use a passkey, the login UI requests a short-lived email one-time code. Ford-service creates and verifies the code; Resend only delivers it. Codes are stored hashed, are single-use and short-lived, and have resend and attempt limits. Responses do not reveal whether an email is registered.
4. Ford-service's Authorization Server returns an authorization code to the BFF callback. The BFF exchanges it server-to-server, stores the tokens in its Redis session, and returns a Secure, HttpOnly, SameSite cookie.
5. For API calls, the BFF retrieves the short-lived access token and forwards it to Ford-service. The browser never receives refresh tokens and does not persist access tokens in local storage.
6. Ford-service issues JWTs with stable `iss`, expected `aud`, `sub`, role, tenant, and scope claims. The existing API Resource Server validates issuer, audience, signature, time bounds, and authorization claims.

Operator enrollment is invite-only. The design needs an operator identity registry and a controlled initial-admin bootstrap. The existing CRM role names map to backend roles as follows: `asesor` to `OPERATOR`, `gerente` to `MANAGER`, and `propietario` to `ADMIN`. Tenant membership is managed by the backend and copied into issued claims; the browser cannot choose its own role or tenant.

## API contract and UI integration

- Keep `ford-ai-service/docs/openapi/frontend-api.yaml` canonical for `/api/v1/**`, OAuth/OIDC discovery and token behavior, WebAuthn endpoints, and error schemas.
- Define BFF routes such as `/api/auth/login`, `/api/auth/callback`, `/api/auth/logout`, `/api/auth/me`, and `/api/v1/**` forwarding in a Ford-web-owned contract. Document cookie/session behavior and error mapping.
- Reconcile the two Ford-web YAML files and regenerate the served JSON instead of maintaining three independent contracts. `/api/openapi.json` and `/api/openapi.yaml` must describe the same current API surface.
- Validate Test Lab request translation against `AiRespondRequest` (`conversationId`, `tenantId`, `messages`, `containsSensitiveData`) and response fields against `AiRespondResponse`.
- Build an endpoint-to-screen matrix for all CRM mock routes. Migrate only operations marked implemented in Ford-service's canonical contract; keep unimplemented screens explicit and avoid fake success responses.

## Security and reliability requirements

- BFF cookies: `Secure`, `HttpOnly`, `SameSite=Lax`, narrow path/domain, bounded expiry, rotation on login, and server-side revocation on logout.
- OAuth: Authorization Code with PKCE, exact redirect URI, one-use state, nonce where applicable, CSRF protection on cookie-authenticated mutations, and no wildcard redirect or CORS origins.
- Passkeys: persistent credentials and challenges, exact origin/RP ID validation, registration limited to authenticated/invited users, and tested recovery behavior.
- Email OTP: cryptographically random values; hashed at rest; short expiry; one-time use; per-account and per-network rate limits; resend cooldown; bounded attempts; generic responses; safe audit events without codes or tokens.
- Resend API key, OAuth client secret, signing keys, and tunnel credentials live only in the existing secret-management/deployment path. JWT signing keys persist across restart and support rotation/JWKS publication.
- CSP is configured separately from CORS. Browser `connect-src` can remain same-origin; required static assets must be enumerated. No wildcard `connect-src`, permissive CORS, or unsafe inline scripts are added to make the proxy work.
- API endpoints remain protected by Spring Security. A Pages Function or Tunnel is transport, not user authorization.

## Verification and rollout

1. Validate the OpenAPI schemas and request/response compatibility; add contract tests between BFF, Spring DTOs, and the UI.
2. Test passkey registration/login, bad challenge/origin rejection, OTP fallback/expiry/replay/rate limits, Resend failures, account lock/recovery, token claims, and logout/revocation with fake email delivery and database-backed integration tests.
3. Test BFF session continuity across process restart, cookie attributes, CSRF, missing/expired token behavior, tenant isolation, and forbidden roles.
4. Deploy candidate Pages and Tunnel origins on temporary hostnames. Verify static delivery, same-origin API proxying, issuer discovery, passkey ceremonies, BFF callback, Test Lab multi-turn persistence, and no proxy loops.
5. Cut over `ford.emme.business` from the current Tunnel route to Pages only after the temporary-hostname checks pass. Keep a documented DNS/Tunnel rollback path and verify emme-1 health and emme-2 PostgreSQL/Redis health after cutover.
6. Migrate the remaining CRM screens by functional area, with one endpoint contract and regression suite per area.

No production route, identity provider, Resend API key, verified sending domain, Pages project binding, or operator enrollment list was changed or confirmed during this design review. Those must be checked before implementation and deployment.

## Success criteria

- Operators open the CRM, UI, API, and auth paths at `https://ford.emme.business`.
- Passkey login is primary; email OTP fallback works and is guarded against replay, enumeration, and brute force.
- Browser sessions use secure cookies; access and refresh tokens never enter local storage or UI state.
- Spring-issued JWTs satisfy the same backend role/tenant authorization boundary used by protected APIs.
- Test Lab succeeds against emme-1 and resumes the same persisted conversation after a UI refresh and backend restart.
- OpenAPI and generated/validated frontend types agree with runtime behavior; no API returns a mock success in production.
- CORS is not needed for browser calls to CRM APIs; CSP is explicit and restrictive.
- The existing Cloudflare hostname cutover has a verified rollback path.
