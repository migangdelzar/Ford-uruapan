# Ford Catalog Readiness Prep Design

## Goal

Make the Ford catalog release as ready as this workspace can safely make it,
while keeping production and shared test databases untouched. Local changes,
tests, and evidence must reflect the observed target baseline rather than mark
unverified gates as passed.

## Approved scope

- Inspect and correct local design/runbook contradictions, especially the
  requirement that PostgreSQL's built-in `public` schema remain present and
  empty.
- Add regression coverage for the observed `ford_service` baseline: split
  Liquibase histories, application relations duplicated between `ford` and
  `public`, and fail-closed migration behavior when a relation is in an
  unreviewed schema.
- Run local checks in the cheapest safe order. Do not claim integration or
  restore verification if required database infrastructure is unavailable.
- Preserve the approved archive and manifest hashes. Investigate the recorded
  source-content mismatch without editing either value.
- Keep a readiness ledger that distinguishes local evidence from external
  blockers and assigns the latter to the source/data owner or authorized DBA.

## Out of scope

- No writes to `emme-1`, `emme-2`, production or shared test databases, object
  storage, Vault, DNS, Cloudflare, or deployment configuration.
- No Liquibase execution, data load, schema cleanup, extension relocation,
  application deployment, or `FORD_CATALOG_TOOLS_ENABLED` change.
- No selection of `ford` or `public` as authoritative if their data differs.
  Compare a verified restore first; stop for owner direction if copies differ.
- No archive replacement, manifest/hash edits, `CASCADE`, or dropped `public`.

## Evidence and known constraints

- The feature commit is local to this workspace. Existing unrelated dirty files
  must remain untouched.
- Read-only checks found the active Service database is `ford_service`; its
  schemas include `ford` and `public`, and app tables overlap between both.
  The database has separate Liquibase histories in both schemas. The `ford`
  role is superuser. The live baseline is not the clean baseline assumed by
  changesets 025/026.
- Changeset 025 rejects relations in unreviewed schemas. The confirmed
  `ford.ai_execution` relation makes its present guard incompatible with the
  live baseline. The guard must not be bypassed; migration behavior needs a
  tested reconciliation design.
- The approved archive SHA remains
  `a1fe738999b3d1148d70d8cb89b4b8e998bfcdc3f7f6304570f06e8c74c1106d`.
  Recorded exporter content mismatches remain unresolved. The current local
  Docker daemon is unavailable, so local restore/export reproduction may be
  blocked.
- Readiness requires external evidence: verified full-database backup and
  isolated restore, authoritative resolution of any differing row copies,
  source-owner resolution of hash mismatches, DBA role/ledger/permission audit,
  migration rehearsal, protected release approvals, and immutable artifact.

## Design

1. **Make the intended contract consistent.** Update operational and cutover
   documents to keep `public` present but empty, preserve the current two
   application schemas, keep tools disabled through verification, and remove
   superseded instructions that conflict with this contract.
2. **Reproduce migration guards in tests.** Add focused tests that represent
   duplicated operational relations and split histories. Tests must prove the
   migration detects ambiguity and fails before changing data; do not bless a
   guessed merge policy. Use a disposable database for integration tests only.
3. **Design reconciliation from evidence.** Compare row counts and canonical
   hashes for same-named tables on a verified restore. If rows differ, stop for
   a source-owner decision. Only after ownership is explicit, implement a
   migration that preserves all source data and history, makes one Liquibase
   ledger authoritative, sets least-privilege roles, and leaves `public`
   empty. Do not modify an already-applied changeset; introduce a versioned
   correction or a reviewed bootstrap path.
4. **Resolve snapshot integrity separately.** Compare exporter output with the
   approved manifest on the exact approved source. Preserve the archive and
   expected hashes. Generate no snapshot SQL until relation inventory, row
   counts, and every content hash pass.
5. **Verify in order.** Run unit/script tests, then integration tests, then
   backup/restore and migration rehearsal on an isolated production-like copy.
   Validate schema placement, ledger checksums, extension ownership, reader
   grants/denials, `public` emptiness, app-data preservation, and catalog row
   hashes. Only after all checks pass may the normal protected release process
   deploy the artifact with tools off; tool enablement stays a separate
   explicit release approval.

## Failure handling

- Missing Docker/restore environment, source identity, backup proof, or owner
  decision is a blocked gate, not a pass.
- Any mismatch, duplicate with differing rows, ledger/checksum conflict,
  unexpected public object, need for `CASCADE`, elevated runtime privilege, or
  failed ACL check stops implementation at that gate.
- Keep production untouched. Record sanitized evidence and the exact next
  owner/action in `docs/operations/production-rollout-readiness.md`.

## Acceptance criteria

- Local contract and regression tests pass for the explicitly supported
  baseline; integration/restore tests are reported only when actually run.
- No approved archive/hash changed, no production/shared-test write occurred,
  and unrelated workspace changes remain untouched.
- Every readiness item is marked passed only with fresh evidence; unresolved
  owner/DBA/restore/release gates stay clearly blocked.
