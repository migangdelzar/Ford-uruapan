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

## MCP-grounded Ford responses

- [x] Add optional tenant-gated MCP client and expose only read-only Ford catalog tools.
- [x] Route catalog, offers, specs, and comparisons to non-overlapping tool families.
- [x] Add deterministic citation rendering and remove URL query/fragment data.
- [x] Align classpath system prompt and DB operator instructions with available tools.
- [x] Add focused tests first, then run service checks and deployment preflight.
- [ ] Commit, push, deploy app to emme-1, and verify DB/app health with emme-2 unchanged.

### Working notes

- Ford Service catalog is tenant-scoped; the scraping MCP contains global Ford Uruapan data. MCP callbacks must only be offered to the configured Ford Uruapan tenant.
- MCP URL, bearer token, and allowed tenant must be runtime config; defaults keep MCP disabled.
- Existing deployment app rollout targets emme-1 and runs health checks against the database on emme-2; never run data deployment for an application-only release.

## Working notes

- Keep OpenRouter only for response generation.
- Keep rules fallback safe when TypeSafe is absent or fails.
- Never log or persist raw provider payloads or secrets.
- Human handoff remains controlled by `FORD_HUMAN_HANDOFF_ENABLED`.
