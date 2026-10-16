# Protected runtime Vault grant management

## Goal

Grant only `ford-runtime-data-emme2` access to `ford-prod-mcp-access-client-secret` and manage this policy through Terraform using durable OCI remote state and a protected GitHub Actions apply.

## Design

- Keep the existing Terraform runtime Vault policy as the single source of truth. Add only the missing emme-2 secret name.
- Use OCI Object Storage native Terraform backend with a dedicated versioned bucket and a distinct state key. Terraform locking serializes operations.
- Use a dedicated OCI API user for Actions, not a developer administrator key. Grant state object access only on the dedicated bucket, read access to existing runtime dynamic groups, and conditional manage access only to policy `ford-runtime-secret-read`.
- GitHub workflow is manual and main-only. First adoption imports the existing two dynamic groups and policy. Plan artifacts are short-lived. Apply is a separate protected job using the repository's `production` environment reviewer.
- All unrelated Terraform resources remain disabled. A plan containing compute, storage, or other IAM changes must fail a guard before apply.

## Safety

No Vault values enter Terraform. The policy names only the MCP Access client secret. Do not destroy existing IAM resources. State bucket versioning is enabled; no expiration lifecycle is configured.
