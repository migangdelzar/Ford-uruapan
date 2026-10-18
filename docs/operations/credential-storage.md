# Credential storage and rotation

## Storage by runtime

| Consumer | Store | Notes |
| --- | --- | --- |
| Ford backend on OCI Compute | OCI Vault | The Compute instance should use an instance principal and a narrowly scoped dynamic group policy. Store runtime values in Vault; keep only non-secret configuration and secret OCIDs in deployment config. |
| Ford web Pages project (`emme-crm-ui`; legacy rollback project `ford-web-ui`) | Cloudflare Pages bindings | The current Pages Functions proxy only uses `FORD_ORIGIN_URL`, which is a URL rather than a credential. Add a Pages secret only when a Pages Function starts consuming one. |
| GitHub Actions | GitHub Actions environment secrets, only when a workflow needs an external credential | Current container publishing uses the automatically scoped `GITHUB_TOKEN`. Do not copy backend runtime credentials into GitHub. |
| Local development | macOS Keychain | Keep local provider/API credentials in Keychain. The scraping service can use its local database and local embedding provider without production credentials. |

## Current migration state

**Current cutover status (2026-10-18):** Ford Service's protected release path supports and requires OCI Vault configuration, but actual production use is unverified until the protected workflow and both-host checks pass. Local Ford-scraping source no longer contains an MCP runtime or MCP deploy task. Production instance-principal reads and the deployed data/collector consumers still require protected verification. Do not infer that the production MCP footprint or its stored credentials have been removed: keep the old deployment and credentials available for rollback until both hosts pass the protected Java catalog release and cleanup checklist in [`tasks/todo.md`](../../tasks/todo.md). Dated notes below record earlier migration states and are not current deployment instructions where they mention MCP runtime migration.

### DeepSeek production chat key

- Ford Service production uses DeepSeek's OpenAI-compatible endpoint at `https://api.deepseek.com` with model `deepseek-flash`.
- The DeepSeek-issued key is stored in OCI Vault and mapped as `FORD_VAULT_SECRET_OCID_OPENAI_API_KEY`; Spring AI reads the resulting runtime property as `OPENAI_API_KEY` because the client uses Spring AI's OpenAI-compatible API.
- The authenticated DeepSeek `/models` endpoint and Spring AI synthetic tool-call preflight passed on 2026-10-16. This verifies the existing Vault value; it does not mean production has been switched yet.
- DeepSeek remains primary. Production allows one OpenRouter fallback attempt after DeepSeek's single repair; OpenRouter stays ineligible unless its API key is in OCI Vault and both tool-calling and structured-output capabilities have been verified. Set the optional production variable `FORD_VAULT_SECRET_OCID_OPENROUTER_API_KEY` after provisioning that secret. Keep both verification flags false until live preflight proves them. TypeSafe/Jev remains a separate classifier with rules fallback; it is not a chat-generation fallback.

- Deployment tooling still supports the legacy local `deployment/config/oci.env` file, but migrated values should be read from `com.ford.uruapan.deploy` using `FORD_KEYCHAIN_SERVICE=com.ford.uruapan.deploy`. The ignored file is being retired from local use after Keychain verification.
- Generated Ansible inventory no longer includes secret values. The existing local inventory was scrubbed of 18 secret fields while preserving non-secret settings and mode `0600`. Ansible reads environment-mode values from its controller process environment; secret-bearing template, registry login, database password update, and Vault/runtime credential tasks use `no_log` where applicable.
- `mise run deploy:vault-secret -- create PROPERTY` prompts for a new value without terminal echo, sends its encoded request to OCI through stdin, and stores only the returned Secret OCID in the ignored `deployment/config/vault-secret-ocids.env` file. Use `rotate PROPERTY SECRET_OCID` to create a new current version for an existing secret. These commands do not read `oci.env`.
- The old `mise run deploy:secrets` command is disabled so it cannot add more values to `oci.env`.
- Local commands load credentials from macOS Keychain through `ford-ai-service/scripts/with-keychain-secrets.sh COMMAND [ARG ...]`. The default service is `com.ford.uruapan.local`; set `FORD_KEYCHAIN_SERVICE=com.ford.uruapan.deploy` for deployment tooling. Each Generic Password account is the environment variable name. `keychain-secrets.swift` imports JSON from stdin, adds missing items without overwriting existing items, and emits only account names. `exec-with-keychain-env.py` places values in the child process environment and execs the requested command. The wrapper itself prints no values.
- Bitwarden master credentials are held under `com.personal.bitwarden`; `BW_SESSION` remains ephemeral and should be obtained through a fresh Bitwarden unlock when needed. A no-longer-used BFF OIDC client secret is isolated under `com.ford.uruapan.legacy` pending identification of its issuer and safe revocation.
- The deploy loader accepts only validated `FORD_VAULT_SECRET_OCID_*` entries from that separate mapping file. The app template passes those OCIDs to the Spring loader; it does not put Vault values into the app environment when `FORD_SECRETS_PROVIDER=oci-vault`.
- Earlier host inspections found environment-mode app, data, MCP, and collector files containing runtime values. The present release gate requires the Service app to use OCI Vault. Current instance-principal access and data/collector secret consumers have not been verified in this cutover; retain their current host state and do not claim Vault readiness until protected checks pass on both hosts. The production MCP runtime is pending retirement after the Java catalog cutover, not a target for new deployment work.
- The backend repo now ignores `deployment/config/oci.env` and its temporary variants, while explicitly allowing the tracked example file.
- OCI Vault `ford-runtime-secrets` and a software-protected encryption key are created. Instance principals for `emme-1` and `emme-2` have separate dynamic groups and a policy restricted to named secrets in that Vault.
- The Spring app has an OCI Vault startup loader using the Compute instance principal. It is opt-in through `FORD_SECRETS_PROVIDER=oci-vault`; its default remains `environment` until replacement values and secret OCIDs are provisioned.
- For the requested test, current configured values were imported into 20 active `ford-test-*` OCI Vault secrets. The ignored `vault-test-secret-ocids.env` file contains only their OCIDs. The production `vault-secret-ocids.env` mapping was not changed, and no runtime was switched to these test values.
- The test import intentionally reuses current configured values only for testing. Treat them as compromised for production: create issuer-approved replacements before production use, and never copy the test mapping into production.
- The data-host and collector secret paths still require verification. MCP source/deployment code has been retired locally, while deployed MCP resources remain pending the post-cutover cleanup gate. Follow the consolidated release checklist for current sequencing.
- Cloudflare Pages does not need a secret for the current request proxy. The Pages project has not been changed.
- GitHub Actions does not need a manually supplied credential for the current container publishing workflow. GitHub secrets were not changed.

## Required rotation

Treat every credential value from the local deployment config as compromised. Rotate credentials at their issuing system, then update the runtime consumer and verify it before revoking the old value. This includes database/cache/operator credentials, signing material, provider API keys, messaging/webhook credentials, MCP/Cloudflare Access tokens, observability credentials, registry credentials, and OCI API credentials if configured.

For production, never copy old exposed values into a new secret store. Create replacement values or provider-issued tokens, update their consumers, verify health, then revoke the old values. The test-only import above is the explicit exception requested for this test and must not be promoted to production. Keep an inventory of secret names, owner, issuer, consumer, rotation date, and verification status; do not record secret values.

## Migration sequence

1. For test-only imports of current local config values, run `mise run deploy:vault-secret -- import-current-test`; the task stores configured values as `ford-test-*` entries and uses a separate ignored OCID mapping. For production, rotate issuer-managed keys and internal credentials first, then use `mise run deploy:vault-secret` to enter replacements and record only their OCIDs in the production mapping.
2. Use the protected runtime-Vault Terraform plan/apply process only after reviewing its exact policy diff; do not add grants for the retired MCP runtime.
3. Verify instance-principal access from the deployed app container and each active runtime host before setting `RUNTIME_VAULT_READY`. Verify production MCP removal only after the Java catalog release and rollback window pass.
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
