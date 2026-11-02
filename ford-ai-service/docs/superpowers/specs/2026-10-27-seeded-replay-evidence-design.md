# Seeded Qwen Replay Evidence Design

## Goal

Resume a local webhook replay from previously validated assistant replies without bypassing existing catalog-evidence safety checks. Seeded turns remain diagnostic and never count as final all-live acceptance.

## Design

The seed reader will load each committed turn's assistant reply and safe tool trace summary from the source transcript. Captured trace fields are limited to tool name, successful outcome, evidence count, and trace origin; no raw tool arguments or personal data are included. A requested prefix cannot cross a failed-turn boundary.

The reader also extracts the source catalog snapshot SHA-256. Seed configuration must match it against the active snapshot before any reply or trace is used. A mismatch fails closed. Seeded replies and their historical tool traces carry explicit `RECORDED_SEED` provenance in the replay artifact and telemetry.

Existing replay safety assertions remain unchanged in meaning: catalog expectations still require a successful allowed tool trace with positive evidence count. Seed traces satisfy that check only for turns whose replies come from the validated seed prefix and only after snapshot-hash validation. Missing or malformed trace evidence does not satisfy the gate. Final acceptance still requires a fresh, unseeded 100-turn live-Qwen run.

## Boundaries and failure handling

- Seed mode remains opt-in and integration-test-only.
- Failed or uncommitted turns cannot be loaded as seeds.
- Snapshot-hash mismatch, missing required trace data, invalid tool identifiers, or malformed counts fail closed.
- Comparison precision remains diagnostic; this does not add or relax a precision gate.
- Ordinary logs remain free of raw messages, tool arguments, and secrets.

## Verification

Unit tests cover trace parsing, malformed/missing trace rejection, failed-turn boundaries, snapshot mismatch, and provenance. Integration tests verify seeded trace propagation through webhook replay and unchanged safety/no-handoff assertions. Focused tests run before any Qwen replay; final all-live acceptance remains separate.
