# Protected runtime Vault identity and policy management

## Goal

Manage the existing emme-1 app and emme-2 data instance principals with Terraform and grant each host only named runtime secret-bundle reads from the configured Ford Vault and compartment. The policy covers the current Ford Service and scraper runtime consumers; the former MCP-only access grant is not part of the desired contract.

## Policy contract

- `deployment/terraform/runtime-vault-policy.json` is the single source for the two dynamic-group names and their exact secret-name lists.
- Terraform uses that contract to construct the policy. The protected plan guard reads the same file and requires the planned policy statements to equal the exact configured set.
- The guard also requires every statement to target the expected Vault and tenancy/compartment, and to grant only `read secret-bundles` to one named group for one named secret.
- Any extra, missing, duplicate, unknown, differently scoped, or broader statement fails before apply. Policy metadata and attachment fields must remain unchanged. The guard also rejects unrelated resource changes and dynamic-group changes other than an unchanged-value sensitivity update.

## State and release controls

- Use the private, versioned `ford-terraform-state` Object Storage bucket and the key `ford-uruapan/runtime-vault-identity/terraform.tfstate`; retain state locking and do not add an expiration rule.
- Use the dedicated OCI API user and protected GitHub Actions secrets. Do not use a developer administrator key.
- Import both existing dynamic groups and the existing policy before Terraform manages them.
- Run the manual workflow from protected `main`. Review the plan artifact before approving the separate protected apply job.
- Verify both production instance principals can read only their intended named secrets after apply. Do not place Vault values in Terraform variables, state, logs, or repository files.

## Rollback and failure handling

Plan validation is fail-closed. A rejected plan does not apply. If an approved policy change causes a runtime read failure, restore the last reviewed policy through the same protected Terraform workflow and verify both app and data consumers before closing the incident.
