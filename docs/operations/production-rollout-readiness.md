# Production Rollout Readiness

Status: **blocked from production enablement**. Local workflow and deployment implementation may proceed, but no production deploy job may run until every blocking gate below is cleared and rechecked.

Checked on 2026-10-12 from the local workspace and authenticated GitHub CLI session. No secret values were read or recorded.

## Verified repository and GitHub state

| Check | Result |
| --- | --- |
| Ford Service GitHub repository | `migangdelzar/Ford-uruapan`, private, default branch `main`. |
| Ford-web GitHub repository | `migangdelzar/Ford-web`, public, default branch `main`. |
| Ford-scraping GitHub repository | No `origin` remote in the local checkout and no `Ford-scraping` repository visible under the authenticated account. The local directory is on `chore/platform-secret-management`. |
| Ford Service deployment environments | Only `talented-purpose / production` is listed; it has no protection rules. A separate protected environment named `production` is not configured. |
| Ford-web deployment environments | No environment is configured. |
| Ford Service private action access | Actions private-repository access level is `none`; target repositories cannot yet call a shared private action. |
| GitHub CLI | Authenticated as `migangdelzar`; read-only metadata queries succeeded. |
| Cloudflare Wrangler | `wrangler whoami` reports unauthenticated. Pages project Git integration and deployment ownership remain unverified. |

## Verified local deployment shape

- Terraform budgets two VMs at 1 OCPU and 6 GB each, with a configured total ceiling of 2 OCPU and 12 GB. A second VM is outside that configured budget.
- Ford Service Compose binds the app to `127.0.0.1:8080`; checked-in files do not define the public Ford Service ingress or a switchable reverse proxy.
- MCP Compose runs Cloudflare Tunnel with host networking and publishes MCP on local port 3001. The active Tunnel ingress mapping is not in the checked-in files.
- CPU/memory headroom under production load has not been measured. Same-host candidate container capacity is unknown.
- Ford Service has scheduled outbox, ingestion, conversation-publication recovery, and graph-checkpoint cleanup jobs. Dual-version execution safety has not been proven by integration tests yet.
- The Spring Vault loader work is present in the current user worktree. Vault readiness for data, MCP, and Collector is not confirmed; production deploy must remain disabled until all runtime consumers are checked.

## Gates before enabling production

- [ ] Create or identify the intended GitHub repository and remote for Ford-scraping; confirm owner and visibility before pushing its local branch.
- [ ] Configure protected `production` environments and required reviewers in all three target repositories; verify the account plan supports those rules for each repository’s visibility.
- [ ] Allow the three caller repositories to use the private Ford Service composite action.
- [ ] Authenticate Wrangler and verify Pages project ownership and current Git integration; select only one production deployment owner.
- [ ] Read the live Ford Service ingress and MCP Tunnel origin; configure and test a stable upstream switch point without changing public URLs.
- [ ] Measure idle/peak host resources with Ollama and existing services active; confirm blue-green candidate headroom on both OCI VMs.
- [ ] Prove scheduled/background work remains single-effect while old and candidate Ford Service versions overlap.
- [ ] Verify Spring, data, MCP, and Collector all retrieve runtime secrets from the intended Vault/runtime path.
- [ ] Verify the backup command and catalog migration run successfully from the production deployment context.

## Recheck record

Append the date, command or evidence source, result, and operator for each gate. Never include credential values, private keys, API tokens, or customer data.
