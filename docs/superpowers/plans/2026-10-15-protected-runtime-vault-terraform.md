# Plan: Protected runtime Vault policy workflow

- [x] Inspect existing Terraform IAM policy and production Actions environment.
- [x] Add the exact missing emme-2 Vault secret grant.
- [ ] Configure OCI native remote state and protected manual plan/apply workflow.
- [ ] Provision dedicated OCI state bucket and least-privilege Actions identity.
- [ ] Import current IAM resources and verify plan contains only the named policy change.
- [ ] Apply through protected Actions approval, rerun Vault preflight, deploy MCP, and smoke-check production.

## Acceptance

The Terraform diff grants emme-2 only `ford-prod-mcp-access-client-secret`; remote state is versioned and locked; Actions never receives an administrator key; apply waits for production review; deployed MCP passes Vault preflight and public smoke.
