# Frontend authentication contract

The Ford AI backend is an OAuth2 Resource Server. It validates access tokens;
it does not issue login tokens and does not expose `/auth/login`,
`/auth/token`, or refresh-token endpoints.

## Provider configuration

The deployment supplies:

```text
FORD_SECURITY_ISSUER_URI=https://identity.example/realms/ford
FORD_SECURITY_AUDIENCE=ford-ai-service
```

The frontend/BFF discovers the provider from:

```text
${FORD_SECURITY_ISSUER_URI}/.well-known/openid-configuration
```

That metadata provides the real authorization and token endpoints. The
OpenAPI document intentionally uses example identity URLs because the actual
identity provider is deployment-specific.

## Browser login

Use Authorization Code with PKCE:

1. Create a public SPA client or use the BFF's confidential client.
2. Generate a `code_verifier` and `code_challenge`.
3. Redirect the operator to the provider authorization endpoint with:
   `response_type=code`, `client_id`, `redirect_uri`, `scope`, `state`, and
   the PKCE challenge.
4. Exchange the returned authorization code at the provider token endpoint.
5. Send the access token to the API:

```http
Authorization: Bearer eyJ...
```

The recommended production topology is a BFF. The BFF keeps the client
secret and refresh token server-side, stores the browser session in a secure,
HttpOnly cookie, and forwards a short-lived access token to the backend. A
direct SPA integration must not use a client secret and should keep access
tokens in memory rather than localStorage.

## Required token claims

| Claim | Requirement |
|---|---|
| `iss` | Must match `FORD_SECURITY_ISSUER_URI`. |
| `aud` | Must include `FORD_SECURITY_AUDIENCE`. |
| `sub` | Stable operator identity. |
| `exp` | Unexpired access token. |
| `nbf` | Token is currently valid. |
| `roles` | One or more of `OPERATOR`, `MANAGER`, `ADMIN`. |
| `tenant_id` or `tenant_ids` | UUID tenant attributes available to the operator. |
| `scope` | Space-delimited operation scopes. |

Spring maps scopes to `SCOPE_<scope>` authorities and roles to
`ROLE_<role>` authorities. Tenant IDs remain attributes and are checked at
the tenant authorization boundary; the frontend must never fabricate them in
headers or query parameters.

## Scope model

Current live scopes:

- `operator:read` — list handoffs
- `handoff:manage` — create, claim, pause, and resume handoffs

Target scopes in the frontend contract:

- `operator:write`
- `leads:read`, `leads:write`
- `inventory:read`
- `finance:quote`
- `appointments:read`, `appointments:write`
- `bot:manage`
- `events:read`
- `testlab:run`

The identity provider must not grant target scopes until the corresponding
backend operation is implemented and enabled.

## API security responses

- `401`: missing, expired, malformed, or otherwise invalid bearer token.
- `403`: valid token, but missing role, scope, or tenant access.
- `409`: valid authorization, but the resource version or state is stale.

API errors use `application/problem+json` and follow the `ProblemDetail`
schema in `frontend-api.yaml`.
