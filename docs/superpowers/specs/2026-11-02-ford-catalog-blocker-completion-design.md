# Ford Catalog Blocker Completion and Default-On Design

## Goal

Complete the Ford catalog cutover plan with every currently known blocker,
dependency, accountable owner role, required evidence, pass condition, and stop
condition. Permit catalog tools to be on by default only after all data,
database, security, deployment, and approval gates have passed.

## Scope and boundaries

- Update the Ford database cutover plan and production readiness ledger so the
  actual emme-1/emme-2 baseline and unresolved gates are explicit.
- Keep production, shared databases, approved archive bytes and hashes,
  Vault, DNS, Cloudflare, routing, and deployment configuration untouched while
  resolving readiness blockers.
- Require an authorized full `ford_service` backup and isolated restore before
  comparing duplicate application data or rehearsing migrations.
- If duplicated `ford`/`public` rows differ, require an explicit data-owner
  decision; do not choose a winner or merge by assumption.
- Do not use `CASCADE`, drop built-in `public`, bypass a migration guard, or
  edit an already-applied Liquibase changeset to force compatibility.
- Preserve the current application-level missing-configuration fallback:
  `FORD_CATALOG_TOOLS_ENABLED` remains false when unset. Production's intended
  post-gate release configuration explicitly sets it true; an explicit false
  remains the emergency disable switch.
- Do not deploy, run production migrations, load a snapshot, or enable tools as
  part of completing the plan documentation.

## Blocker sequence and acceptance evidence

1. **Source integrity — source/data owner.** Supply the canonical JSONL hash
   generation procedure and per-relation evidence for the two mismatched
   hashes. Re-run the exporter against the unchanged approved archive. Pass
   only when identity, relation inventory, counts, and every approved content
   hash match and the reviewed snapshot artifact has an immutable identity.
2. **Full backup and restore — DBA/operator.** Produce an approved, externally
   retained full `ford_service` backup, hash and manifest it, and restore it to
   an isolated production-like PostgreSQL instance. Pass only when application
   data, schemas, roles/grants, and both Liquibase histories are present and
   independently verified.
3. **Data authority and ledger provenance — data owner and DBA.** Compare
   same-named relations row-for-row on that restore; identify owners, counts,
   constraints, dependencies, and both ledgers' changesets/checksums. Differing
   data requires a written owner decision and reviewed reconciliation mapping.
   Pass only when one migration-history authority and a lossless, reviewed data
   mapping are established.
4. **Migration and extension design — DBA and application/migration owner.**
   Reconcile changeset 025 with the observed `ford.ai_execution` and other
   duplicate relations; reconcile changeset 026 with `pgcrypto` in `public`,
   extension ownership/versions, dependencies, and supported relocation.
   Define a versioned correction or reviewed bootstrap path without weakening
   fail-closed checks. Pass only when the exact migration is reviewed against
   the restored baseline and active application version.
5. **Least privilege and schema contract — DBA/security owner.** Replace the
   superuser application role with reviewed migration/runtime identities;
   audit `ford_catalog_reader` effective grants. Pass only when the reader can
   select approved catalog views, cannot write or access metadata/private
   capture data, and `public` remains present and empty of application and
   extension objects.
6. **Migration rehearsal — migration owner and QA.** Apply exact reviewed
   changesets and the approved snapshot only to the isolated restore. Verify
   application-data preservation, one authoritative Liquibase ledger,
   extension placement, schema inventory, row counts/hashes, reader allow and
   deny probes, and old/candidate application compatibility. Pass only with
   repeatable results and no unknown object, checksum conflict, data loss,
   permission error, or `CASCADE` requirement.
7. **Release readiness — release owner and platform/security owners.** Verify
   full read-only target inventory, deployment inventory, host capacity and
   disk headroom, runtime Vault identity/secret access, CI, reviewed source
   SHA, immutable image digest/attestation, protected environments/reviewers,
   rollback image, and tested backup-restore procedure. Refresh any stale
   repository/workflow status immediately before release. Pass only when all
   required protected checks and approvals are evidenced.
8. **Protected rollout — authorized release and migration owners.** Promote
   sequentially through the approved environment path, with a fresh backup and
   approval per target. Keep catalog tools off during database and Service
   verification. Pass only when both hosts show the approved artifact and
   datasource, health is good, schema/data/ACL checks pass, and rollback
   artifacts remain available.
9. **Default-on activation — release owner.** After gates 1–8 pass, explicitly
   set `FORD_CATALOG_TOOLS_ENABLED=true` in protected production configuration
   as the normal desired state. Do not change the code fallback from false.
   Run synthetic exact-match, no-match, citation, and unavailable-catalog
   checks; record approval and observation. An explicit false must disable the
   callbacks and be tested as the rollback control.

## Approaches considered

- **Recommended — focused plan and readiness-ledger update.** Add the ordered
  owner/evidence gates above to the existing Ford cutover plan and record
  current blockers in the readiness ledger. This keeps one operational
  sequence and avoids duplicating the broader multi-repository release plan.
- **Separate blocker checklist.** Easier to assign independently, but risks
  divergence from the cutover plan and readiness source of truth.
- **Rewrite the full release program.** Could unify repository, Service,
  scraper, Web, Vault, and catalog work, but is broader than this request and
  would obscure the immediate database blockers.

## Failure and rollback behavior

- Missing owner evidence, backup, restore, decision, approval, or test result
  remains blocked; it is never inferred as passed.
- Any discrepancy, unreviewed relation, unsupported extension move, unsafe
  grant, failed health/data check, insufficient capacity, or rollback gap stops
  promotion.
- Keep tools off until activation gate 9. If activation checks fail, set the
  protected flag explicitly false and follow the approved application rollback
  and verified external database restore procedure. Do not automatically
  reverse Liquibase migrations.

## Acceptance criteria

- Existing cutover and readiness documents list every gate in dependency
  order, responsible owner role, evidence, pass criteria, and stop criteria.
- Approved archive and expected hashes remain unchanged; no production or
  shared-database write, deployment, snapshot load, Vault/DNS/routing change, or
  tool enablement occurs while documenting the plan.
- Application fallback remains fail-closed when the tool flag is absent;
  default-on means explicit protected production configuration only after all
  prerequisites and approvals pass, with a tested explicit-off switch.
- Open blockers remain visibly blocked until fresh evidence is recorded.
