# Jev classification implementation

- [x] Add TypeSafe dependency/configuration and prove property binding.
- [x] Add typed decision vocabulary and policy.
- [x] Add TypeSafe adapter with deterministic fallback.
- [x] Propagate decisions into response generation and tool policy.
- [x] Persist normalized classifier evidence.
- [x] Wire optional TypeSafe startup behavior and diagnostics.
- [x] Add optional guardrail/self-refine configuration.
- [ ] Add end-to-end verification and operations docs.
- [ ] Run full verification and finish branch.

## Working notes

- Keep OpenRouter only for response generation.
- Keep rules fallback safe when TypeSafe is absent or fails.
- Never log or persist raw provider payloads or secrets.
- Human handoff remains controlled by `FORD_HUMAN_HANDOFF_ENABLED`.
