# Task 6 Report — Pin Shared Action and Wire Protected Catalog Vault Reference

## TDD evidence

- Added release-workflow contract assertions before changing the workflow.
- RED: `cd ford-ai-service && python3 -m unittest scripts.tests.test_oci_action_contract` failed as expected: the release action SHA was stale, the protected catalog Vault variable was absent, and the action input was not wired.
- GREEN: after workflow wiring, the focused test suite passed (22 tests).
- Full Python contracts passed: `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` (105 tests).

## Changes

- `.github/workflows/release.yml`: exposed only `FORD_VAULT_SECRET_OCID_FORD_CATALOG_PASSWORD` from `vars` and passed it as `service-catalog-password-secret-ocid`.
- `.github/workflows/release.yml`: pinned the shared action to reviewed merge SHA `5937d8bddb4da6570bd2d929c8120a3c6821f3a2`.
- `.github/workflows/release.yml`: retained the catalog-tools `false` default and existing release, reader, and backup gates.
- `ford-ai-service/scripts/tests/test_oci_action_contract.py`: asserted exact SHA, protected OCID mapping, action input mapping, and false-by-default catalog flag; disallowed a direct catalog password env key.

## Self-review

- No catalog password value or secret was added; only the protected variable containing its Vault secret OCID is passed.
- Catalog tools remain disabled by default.
- No production configuration or unrelated untracked files were changed.

## Status

- Ready to commit as `ops: wire catalog Vault reference into Service release`.
