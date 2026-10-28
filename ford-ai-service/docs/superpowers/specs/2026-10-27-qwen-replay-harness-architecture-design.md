# Qwen Replay Harness Architecture

**Status:** Design approved for documentation; implementation not yet approved.

## Problem

The current seeded replay adapter returns a recorded assistant reply instead of
calling Qwen. The real webhook, conversation graph, memory projection, and turn
commit still run. Recorded tool traces are transcript metadata; they do not
execute catalog tools or create live tool-evidence events. Therefore a seeded
turn can test conversation plumbing, but cannot prove that a live catalog call
occurred or returned evidence.

Turn 5 exposed this boundary: the replay used its recorded reply, while the
integration gate required a real `search_vehicles` evidence event. Separately,
turns such as 86 need focused live diagnosis without paying the cost of another
full 100-turn Qwen run.

## Goals

- Preserve one production `ConversationGraph` and webhook path.
- Support fast, focused live-Qwen diagnosis from a validated conversation
  checkpoint.
- Keep a separate, clean 100-turn all-live acceptance gate.
- Make seeded, fixture, and live-model evidence visibly distinct.
- Never count transcript metadata as a real tool call or catalog evidence.
- Keep all replay databases and artifacts isolated from production.

## Non-goals

- Add autonomous AI agents or a second conversation graph.
- Weaken safety, evidence, ambiguity, or no-handoff checks.
- Treat a seeded 100-turn run as final live acceptance.
- Reconstruct prior catalog calls from tool name/count metadata alone.

## Architecture

Use one test harness with two explicit execution modes. Both use the existing
Spring application context, webhook, `ConversationGraph`, and turn-commit path.
Only state setup and model execution differ.

### 1. `CHECKPOINT_LIVE_WINDOW`

Restore a versioned, test-only checkpoint immediately before the target turn.
The checkpoint contains only state needed for continuity: synthetic
conversation identity/state, recent user/assistant messages, memory summary,
durable memory facts, and a conversation version. It is not an assertion that
prior model or catalog calls ran during this test.

Send the selected target turn(s) through the real webhook and graph. Qwen and
authorized read-only catalog tools run live. Validate committed turns,
tool/evidence provenance, response safety, exact vehicle identity, and zero
handoffs for each target turn.

Checkpoint manifest records schema version, scenario hash, catalog snapshot
label/hash, checkpoint turn, and artifact origin. Reject incompatible or
incomplete checkpoints before sending a webhook. Do not include customer data;
use synthetic scenario content only.

### 2. `FRESH_FULL_LIVE`

Start with a new disposable database and empty conversation. Run all 100 buyer
turns through the same real webhook and graph with live Qwen and real tools.
Seed adapter must be absent. This mode alone can satisfy final acceptance:

- exactly 100 turns committed in order;
- every turn marked `LIVE_QWEN`;
- zero seeded replies and zero handoffs;
- existing safety and catalog-evidence assertions pass.

### Seeded sequential replay (supporting diagnostic, not an acceptance mode)

Keep assistant-reply seeding only for validating webhook, memory, and commit
plumbing across a long transcript. Report each turn as `RECORDED_SEED`; recorded
tool traces remain labeled `RECORDED_SEED` and are never persisted or counted as
live tool evidence. Assertions for live catalog calls apply only to live turns.
Any catalog behavior being evaluated must run in `CHECKPOINT_LIVE_WINDOW` or
`FRESH_FULL_LIVE`.

## Provenance and reporting

Every replay artifact and telemetry record carries an explicit mode and per-turn
origin:

- `LIVE_QWEN` — actual model execution;
- `RECORDED_SEED` — saved assistant reply;
- `CHECKPOINT_FIXTURE` — restored conversation/memory state;
- tool evidence remains separately identified as live tool execution or
  recorded metadata.

Reports must show seeded count, live count, fixture/checkpoint identity, catalog
snapshot hash, scenario hash, and handoff count. A report must fail closed if
provenance is missing or contradictory.

## Failure handling

- Invalid checkpoint, scenario hash, or catalog hash: stop before webhook.
- Seeded message mismatch or missing seed turn: stop; never silently fall back
  to live Qwen.
- Target-turn model/tool failure: record the exact failed turn and preserve the
  checkpoint for a deterministic rerun.
- Ambiguous model/year/trim: preserve `UNKNOWN`/ambiguous status; request
  clarification rather than selecting a nearby variant.
- A seeded run cannot emit the final all-live acceptance status.

## Acceptance tests

1. Seeded adapter test proves saved replies make no model/tool call and are
   labeled `RECORDED_SEED`.
2. Checkpoint validation tests reject wrong scenario/snapshot hashes, malformed
   state, or incorrect turn position before webhook submission.
3. Focused live-window integration test restores a checkpoint and proves the
   target request uses Qwen and real tool callbacks, commits, preserves safety,
   and creates no handoff.
4. Full-live integration gate proves a clean database, 100 live model turns,
   zero seeded turns, 100 commits, and zero handoffs.
5. Transcript, telemetry, and summaries cannot describe a seeded run as
   all-live acceptance.

## Operational separation

Each mode gets a distinct Gradle task or explicit mode property, unique run ID,
separate transcript/telemetry paths, and a disposable database. The default
developer run remains cheap and offline. Live Qwen modes remain opt-in and must
not run concurrently against shared Gradle result directories.

## Rollout sequence

1. Add typed mode/provenance reporting and checkpoint schema/validation.
2. Add checkpoint restore and one focused live-window test for turns 86–87.
3. Run focused unit/integration checks, then the live window.
4. Keep seeded sequential replay as a plumbing diagnostic only.
5. Run the final fresh 100-turn all-live gate only after focused diagnostics
   pass.
6. Keep catalog snapshot import as the last, separately verified action.
