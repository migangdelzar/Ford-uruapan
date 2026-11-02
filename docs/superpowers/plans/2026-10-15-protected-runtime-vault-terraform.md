# Plan: Protected runtime Vault policy management

Design: [Protected runtime Vault identity and policy management](../specs/2026-10-15-protected-runtime-vault-terraform-design.md).

## Local implementation

- [x] Keep the existing emme-1 app and emme-2 data dynamic groups and policy under Terraform adoption mode; existing identities use `prevent_destroy`.
- [x] Define the exact app/data group names and named secrets once in `runtime-vault-policy.json`; Terraform builds policy statements from it.
- [x] Make the protected plan guard verify the full planned statement set against that contract plus the expected Vault and compartment OCIDs.
- [x] Invoke the same fail-closed guard during both plan review and apply; retain main-only execution, protected environments, short-lived plan artifact, and separate apply approval.
- [x] Add regression coverage for arbitrary broadening, missing grants, wrong Vault/compartment scopes, policy metadata/attachment changes, unrelated resources, valid OCI identifiers, and dynamic-group changes.
- [x] Update the current Terraform documentation to describe the post-MCP-retirement policy and its exact statement guard.

## Protected production work — still pending

- [ ] From protected `main`, run the Terraform workflow in `plan` mode. Verify imports use the exact existing dynamic-group and policy OCIDs; confirm the plan only makes the reviewed policy update and the exact statements pass the guard.
- [ ] Review the plan artifact and apply it only through the `terraform-runtime-vault-approval` environment, retaining both workflow run IDs and approval evidence.
- [ ] Verify the resulting OCI policy and instance-principal access on emme-1 and emme-2. Prove expected secret reads succeed and unrelated secret reads fail; retain sanitized evidence without secret values.
- [ ] Confirm Terraform state bucket versioning, locking, dedicated API identity, and least-privilege state access from protected Actions evidence.

## Acceptance

The Terraform plan contains only the exact configured named `read secret-bundles` statements for the app and data groups in the intended Vault and tenancy/compartment. No plan is applied without artifact review and protected approval. Both runtime identities are verified in OCI after apply; no Vault values enter Terraform state or logs.

## Rollback

Any guard mismatch stops before apply. If post-apply runtime validation fails, restore the prior reviewed policy through protected Terraform apply and verify both runtime consumers before declaring rollback complete.
