# Qwen Turn-31 Diagnostic Replay Design

## Goal

Find the cause of the historical safe-review failure near turn 31 without running the 100-turn acceptance or writing catalog data.

## Evidence and boundary

The available transcript is an older, unverified run. It shows turn 31 returned zero `get_vehicle` evidence, yet the reply stated a price. The replay then stopped at turn 32 with `SAFE_FALLBACK_REVIEW`. It does not include the underlying exception or prove this is the reported failure. Treat it as a reproduction lead, not as root-cause proof.

## Recommended approach

Add an opt-in integration-test diagnostic mode that runs the natural buyer scenario from turn 1 through a caller-selected end turn, capped at 32 for this investigation. Do not seed from the old unverified transcript. Use only the approved local snapshot and disposable PostgreSQL. Keep the existing read-only delivery isolation, no-handoff checks, evidence checks, and safety assertions active. Do not invoke the 100-turn acceptance mode or catalog import.

## Diagnostic output

On success or failure, write a synthetic replay transcript and bounded telemetry for each turn. On failure, retain the turn number, safe failure category, provider/model, provider-call count, tool names/outcomes/evidence counts, memory-shape counts, and sanitized exception class/category. Never write credentials, raw provider errors, or production data. Preserve the exact safe-review reason while distinguishing it from the underlying diagnostic category.

## Data flow

1. Test-only environment opts into diagnostic mode and supplies an end-turn value.
2. Existing preflight validates the local snapshot; existing Testcontainers database receives synthetic fixtures only.
3. The existing webhook and assistant path process turns sequentially from 1 through the selected end turn using live local Qwen.
4. Existing safety and no-handoff gates run after every committed turn. First failure stops the run and records bounded diagnostics.
5. The transcript identifies the run as diagnostic; it cannot count as all-live acceptance.

## Failure behavior

Missing or invalid opt-in settings fail closed before a webhook is sent. Missing snapshot/BGE preflight skips the replay. Any safe-review route, assertion failure, or timeout stops the window and records the exact failing turn plus sanitized diagnostics. No safety assertion is relaxed and no fallback reply is treated as a passing turn.

## Verification

- Unit-test configuration boundaries: mode is off by default, accepts only the explicit diagnostic mode, and rejects an end turn outside 1–32.
- Integration-test the diagnostic runner with a stubbed completion port to prove sequential processing, immediate stop on failure, and diagnostic-only labeling.
- Run the live local-Qwen window only after unit/integration gates pass and local snapshot/model preflight succeeds.
- Keep the full all-live 100-turn acceptance paused until the historical failure is explained and the focused replay passes.

## Out of scope

- Fresh 100-turn acceptance.
- Production or `emme-2` writes, catalog import, or live remote-provider calls.
- Changing assistant safety, retry, fallback, tool authorization, or handoff behavior.
- Seeding turns from unverified transcripts.
