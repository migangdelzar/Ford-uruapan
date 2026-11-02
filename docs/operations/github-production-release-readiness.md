# GitHub Production Release Readiness

> **Historical snapshot (2026-10-14; superseded for current cutover):** Repository names, branch protection, environment configuration, credentials, and release workflow state below are point-in-time observations from the former MCP release topology. Do not use them as current configuration instructions. The active checklist is [`tasks/todo.md`](../../tasks/todo.md); verify current GitHub state through the protected release workflows before taking action. Ford-scraping is the catalog writer/scheduler release; Ford-web is the local CRM Pages release.

Status checked on 2026-10-14: **production releases remain blocked pending runtime Vault and credential setup**. The workflows below deploy to existing OCI hosts and Cloudflare Pages; they do not create infrastructure. Production environments keep their required reviewer.

## Credential placement

Keep credentials in the narrowest scope that needs them:

| Repository and scope | GitHub Actions secrets | GitHub Actions variables |
| --- | --- | --- |
| Ford-uruapan / production | OCI_SSH_PRIVATE_KEY, OCI_DATA_SSH_PRIVATE_KEY, OCI_SSH_KNOWN_HOSTS | OCI_DEPLOY_HOST, OCI_DEPLOY_USER, OCI_DATA_DEPLOY_HOST, OCI_DATA_DEPLOY_USER, OCI_DATA_PRIVATE_IP, FORD_SECRETS_PROVIDER, Spring `FORD_VAULT_SECRET_OCID_*` mappings, Grafana Cloud endpoint/instance ID, Langfuse endpoint/public key, and the Grafana/Langfuse Vault secret OCIDs |
| Ford-scraping / production | OCI_MCP_SSH_PRIVATE_KEY, OCI_SSH_KNOWN_HOSTS | OCI_MCP_HOST, OCI_MCP_PRIVATE_IP, OCI_DEPLOY_USER, OCI_DATA_DEPLOY_HOST, OCI_DATA_DEPLOY_USER, FORD_SECRETS_PROVIDER, FORD_VAULT_SECRET_OCID_POSTGRES_PASSWORD, FORD_VAULT_SECRET_OCID_MCP_HTTP_BEARER_TOKEN |
| Ford-web / production | CLOUDFLARE_API_TOKEN | CLOUDFLARE_ACCOUNT_ID, FORD_WEB_SMOKE_URL |
| Ford-uruapan / repository variables | None | PRODUCTION_DEPLOY_ENABLED, RUNTIME_VAULT_READY |
| Ford-scraping / repository variables | None | PRODUCTION_DEPLOY_ENABLED, RUNTIME_VAULT_READY |
| Ford-web / repository variables | None | PRODUCTION_DEPLOY_ENABLED |
| Ford-uruapan / repository | RELEASE_COORDINATOR_APP_ID, RELEASE_COORDINATOR_APP_PRIVATE_KEY | None |

Host names, account IDs, private IPs, and usernames are configuration, not credentials. Keep them as environment variables. Application provider keys and database credentials belong in OCI Vault and must not be copied into GitHub Actions secrets. GitHub secrets are for workflow credentials such as SSH keys, the Cloudflare deploy token, and the narrowly scoped GitHub App key.

Ford Service's deploy action reads the Grafana Cloud API token and Langfuse
secret key from OCI Vault on emme-1 using the VM Instance Principal. GitHub
Actions receives only the secret OCIDs. The Instance Principal policy must
permit `read secret-bundles` for those exact secrets. The deploy action checks
all Collector settings before its pre-release backup and redacts secret reads
from Ansible output.

The Ford Service `OCI_DATA_PRIVATE_IP` production variable now matches Ford MCP's existing `OCI_MCP_PRIVATE_IP` for emme-2. The test-only Vault mapping file contains `ford-test-*` OCIDs; never copy those OCIDs into either production environment.

Keep the production gate flags at repository scope: the workflow checks them before it enters the protected production environment, so a missing or false flag fails without first waiting for reviewer approval. Other deploy variables and secrets stay in the protected production environment.

Configure values at:

- [Ford Service Actions secrets and variables](https://github.com/migangdelzar/Ford-uruapan/settings/secrets/actions)
- [Ford Service production environment](https://github.com/migangdelzar/Ford-uruapan/settings/environments)
- [Ford MCP Actions secrets and variables](https://github.com/migangdelzar/Ford-scraping/settings/secrets/actions)
- [Ford MCP production environment](https://github.com/migangdelzar/Ford-scraping/settings/environments)
- [Ford Web Actions secrets and variables](https://github.com/migangdelzar/Ford-web/settings/secrets/actions)
- [Ford Web production environment](https://github.com/migangdelzar/Ford-web/settings/environments)
- [GitHub App settings](https://github.com/settings/apps)

The Cloudflare token currently exists in the pages-preview environment, not the production environment. GitHub will not reveal an existing secret value for copying. Add the same restricted token to Ford-web's production environment as CLOUDFLARE_API_TOKEN; do not paste it into source control or a chat.

The release-all workflow currently has no repository-level GitHub App credentials. Create or select an App installed only on Ford-uruapan, Ford-scraping, and Ford-web, with Actions write and Contents read, then add its App ID and private key under the Ford Service repository secrets. The release workflow checks these values before attempting token creation.

## Verified infrastructure checks

Status checked on 2026-10-15:

- Ford Service v0.1.4 passed tagged CI, but its production attempt stopped before candidate startup or route switching. The pre-release database/Redis backup and monthly timer installation succeeded; Collector rendering then failed because the shared action did not pass its observability configuration. The shared action and Service workflow fixes are being verified before retrying.
- Ford MCP v0.1.2 passed tagged CI, but its production workflow stopped at its readiness gate because the production environment variables `PRODUCTION_DEPLOY_ENABLED` and `RUNTIME_VAULT_READY` were unset. Its tagged container was not deployed.
- Ford Web's production Pages workflow still needs a valid `CLOUDFLARE_API_TOKEN`; do not report a Pages production deploy as ready until that credential is configured and the workflow succeeds.

- The read-only deployment connectivity preflight passed for emme-1 and emme-2 in run [38619942939](https://github.com/migangdelzar/Ford-uruapan/actions/runs/38619942939).
- Both existing VMs use /opt/ford-uruapan for Service, MCP, and database backup files. Release workflows must pass that root explicitly to the shared OCI deployment action.
- Manual backup run [38649721472](https://github.com/migangdelzar/Ford-uruapan/actions/runs/38649721472) created a fresh backup at 20261014T224457Z. Its verification step failed because it searched /opt/ford/backup/archive. Read-only host checks then verified the manifest age, both archive checksums, gzip integrity, and PostgreSQL archive readability (710 archive-list entries). The workflow path is corrected here; rerun its verify operation after this change reaches main.
- Release-all's credential-free version preflight passed for Ford-uruapan v0.1.0, Ford-scraping v0.1.1, and Ford-web v0.1.0 in run [38650061906](https://github.com/migangdelzar/Ford-uruapan/actions/runs/38650061906).
- The Service and MCP production readiness flags are not equivalent evidence of live readiness. Verify each host's Instance Principal access and runtime consumers before setting `RUNTIME_VAULT_READY`; the Service collector secret reads still require an on-host production verification.

## Release operation

1. Run the read-only Deployment Connectivity Preflight and Database Backup Operations verify workflows.
2. Run Release selected Ford repositories with operation validate and the exact versions to be released.
3. After all readiness checks pass, dispatch operation release for the selected repositories. Each repository validates its annotated version tag, exact commit, CI result, and image provenance.
4. Approve each production environment deployment. Service and MCP take a database backup before changes; MCP migration runs before the candidate starts; Service Liquibase changes run during candidate startup. Health checks gate traffic switching and rollback.
5. A coordinator result manifest records each selected version and commit. Releases across repositories are reported independently; the group is not one atomic deployment.

Run an Action's validate or preflight operation for checks that should stay read-only. Use the production environment's required reviewer for any release that can change customer traffic or schema.
