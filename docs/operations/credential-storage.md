# Credential storage and rotation

## Storage by runtime

| Consumer | Store | Notes |
| --- | --- | --- |
| Ford backend on OCI Compute | OCI Vault | The Compute instance should use an instance principal and a narrowly scoped dynamic group policy. Store runtime values in Vault; keep only non-secret configuration and secret OCIDs in deployment config. |
| Ford web Pages project (`emme-crm-ui`; legacy rollback project `ford-web-ui`) | Cloudflare Pages bindings | The current Pages Functions proxy only uses `FORD_ORIGIN_URL`, which is a URL rather than a credential. Add a Pages secret only when a Pages Function starts consuming one. |
| GitHub Actions | GitHub Actions environment secrets, only when a workflow needs an external credential | Current container publishing uses the automatically scoped `GITHUB_TOKEN`. Do not copy backend runtime credentials into GitHub. |
| Local development | macOS Keychain | Keep local provider/API credentials in Keychain. The scraping service can use its local database and local embedding provider without production credentials. |

## Current migration state

- Deployment tooling still supports the legacy local `deployment/config/oci.env` file, but migrated values should be read from `com.ford.uruapan.deploy` using `FORD_KEYCHAIN_SERVICE=com.ford.uruapan.deploy`. The ignored file is being retired from local use after Keychain verification.
- Generated Ansible inventory no longer includes secret values. The existing local inventory was scrubbed of 18 secret fields while preserving non-secret settings and mode `0600`. Ansible reads environment-mode values from its controller process environment; secret-bearing template, registry login, database password update, and MCP authorization tasks use `no_log` where applicable.
- `mise run deploy:vault-secret -- create PROPERTY` prompts for a new value without terminal echo, sends its encoded request to OCI through stdin, and stores only the returned Secret OCID in the ignored `deployment/config/vault-secret-ocids.env` file. Use `rotate PROPERTY SECRET_OCID` to create a new current version for an existing secret. These commands do not read `oci.env`.
- The old `mise run deploy:secrets` command is disabled so it cannot add more values to `oci.env`.
- Local commands load credentials from macOS Keychain through `ford-ai-service/scripts/with-keychain-secrets.sh COMMAND [ARG ...]`. The default service is `com.ford.uruapan.local`; set `FORD_KEYCHAIN_SERVICE=com.ford.uruapan.deploy` for deployment tooling. Each Generic Password account is the environment variable name. `keychain-secrets.swift` imports JSON from stdin, adds missing items without overwriting existing items, and emits only account names. `exec-with-keychain-env.py` places values in the child process environment and execs the requested command. The wrapper itself prints no values.
- Bitwarden master credentials are held under `com.personal.bitwarden`; `BW_SESSION` remains ephemeral and should be obtained through a fresh Bitwarden unlock when needed. A no-longer-used BFF OIDC client secret is isolated under `com.ford.uruapan.legacy` pending identification of its issuer and safe revocation.
- The deploy loader accepts only validated `FORD_VAULT_SECRET_OCID_*` entries from that separate mapping file. The app template passes those OCIDs to the Spring loader; it does not put Vault values into the app environment when `FORD_SECRETS_PROVIDER=oci-vault`.
- Environment-mode app, data, MCP, and collector files still contain runtime values on their target hosts. The data host, MCP service, and collector do not yet retrieve their secrets directly from Vault. Keep production on environment mode until each consumer has been migrated and verified.
- The backend repo now ignores `deployment/config/oci.env` and its temporary variants, while explicitly allowing the tracked example file.
- OCI Vault `ford-runtime-secrets` and a software-protected encryption key are created. Instance principals for `emme-1` and `emme-2` have separate dynamic groups and a policy restricted to named secrets in that Vault.
- The Spring app has an OCI Vault startup loader using the Compute instance principal. It is opt-in through `FORD_SECRETS_PROVIDER=oci-vault`; its default remains `environment` until replacement values and secret OCIDs are provisioned.
- For the requested test, current configured values were imported into 20 active `ford-test-*` OCI Vault secrets. The ignored `vault-test-secret-ocids.env` file contains only their OCIDs. The production `vault-secret-ocids.env` mapping was not changed, and no runtime was switched to these test values.
- The test import intentionally reuses current configured values only for testing. Treat them as compromised for production: create issuer-approved replacements before production use, and never copy the test mapping into production.
- Data-host, MCP, and collector credentials still use the existing deployment environment path. Do not switch production to Vault mode until those consumers are migrated too.
- Cloudflare Pages does not need a secret for the current request proxy. The Pages project has not been changed.
- GitHub Actions does not need a manually supplied credential for the current container publishing workflow. GitHub secrets were not changed.

## Required rotation

Treat every credential value from the local deployment config as compromised. Rotate credentials at their issuing system, then update the runtime consumer and verify it before revoking the old value. This includes database/cache/operator credentials, signing material, provider API keys, messaging/webhook credentials, MCP/Cloudflare Access tokens, observability credentials, registry credentials, and OCI API credentials if configured.

For production, never copy old exposed values into a new secret store. Create replacement values or provider-issued tokens, update their consumers, verify health, then revoke the old values. The test-only import above is the explicit exception requested for this test and must not be promoted to production. Keep an inventory of secret names, owner, issuer, consumer, rotation date, and verification status; do not record secret values.

## Migration sequence

1. For test-only imports of current local config values, run `mise run deploy:vault-secret -- import-current-test`; the task stores configured values as `ford-test-*` entries and uses a separate ignored OCID mapping. For production, rotate issuer-managed keys and internal credentials first, then use `mise run deploy:vault-secret` to enter replacements and record only their OCIDs in the production mapping.
2. Migrate the data host, MCP service, and collector to retrieve their needed secrets from OCI Vault.
3. Verify instance-principal access from the deployed app container and each runtime host before switching production.
4. Move local development and deployment credentials into their separate macOS Keychain services and run commands through `ford-ai-service/scripts/with-keychain-secrets.sh`; never persist `BW_SESSION`.
5. Revoke old credentials only after production health checks confirm the replacements are active.

## Safety rules

- Never search, print, or commit local secret files. Inspect only tracked templates or emit key names and presence metadata.
- Ansible tasks that handle secret values must use `no_log: true`; generated inventory and temporary files must stay mode `0600` or stricter.
- Do not pass secret values as command-line arguments. Avoid persisting decrypted secret values to disk.
- Keep separate credentials for local, staging, and production environments where the issuing system supports it.

## Naming and current local migration

- GitHub Actions deployment hosts and usernames are configuration variables. SSH private keys, known-host data, and API tokens remain secrets.
- Runtime names such as `FORD_DATABASE_PASSWORD` are application configuration keys. The `FORD_VAULT_SECRET_OCID_FORD_DATABASE_PASSWORD` form combines the Vault-mapping prefix with that exact target key; it does not name a second credential. Keep this format until the loader and all ignored OCID maps are migrated together.
- `FORD_MCP_API_TOKEN` and `MCP_HTTP_BEARER_TOKEN` are compatibility fallbacks for canonical `FORD_MCP_TOKEN`. Do not create new aliases; remove old ones only after the deployed consumers are verified.
- The three non-empty SSH key values in the ignored local `oci.env` were added to the `com.ford.uruapan.deploy` Keychain service without overwriting existing entries. The source file remains mode `0600` while any legacy command may still read it. No key values were logged or committed.
