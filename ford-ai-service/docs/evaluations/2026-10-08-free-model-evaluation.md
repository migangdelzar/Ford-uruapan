# Free model workflow evaluation (2026-10-08)

## Scope

All prompts and tool data were synthetic. No customer conversations, Meta delivery,
MCP service, durable database, or Typesafe/Jev classifier were used. Each scenario
started with a fresh in-memory transcript. The separate PostgreSQL/Redis integration
scenarios remain the evidence for durable memory and lead journey behavior; they use
deterministic model/classifier fixtures.

The [OpenRouter free plan](https://openrouter.ai/pricing/) currently advertises 50
requests/day. OpenRouter lists [`openrouter/free`](https://openrouter.ai/openrouter/free/apps)
at zero prompt/completion token pricing and says the router selects free models
dynamically. The comparison used only listed `:free` model IDs. Provider availability
and rate limits prevented a quality comparison for the pinned models.

## Results

| Path | Result | What it establishes |
|---|---:|---|
| `openrouter/free`, 8 scenarios / 11 user turns | 3/8 passed | Live workflow behavior through fixture tools; not production MCP or durable memory |
| Spring AI provider preflight, `openrouter/free` | Passed, one tool callback | OpenRouter free route works through the app's Spring AI adapter for a synthetic read-only tool call |
| Qwen3.8 27B free, 5 representative scenarios | 0/5; HTTP 404 on all requests | No answer-quality result; endpoint/model request was not served |
| Gemma 4 26B A4B free, same 5 scenarios | 0/5; HTTP 429 on all requests | No answer-quality result; provider rate-limited the requests |

### `openrouter/free` failures

- `greeting-and-discovery`: omitted required `search_vehicles` tool call.
- `contextual-follow-up-keeps-vehicle-reference`: first turn omitted the required search; second answer did not satisfy the expected efficiency detail.
- `comparison`: exceeded the evaluator's three-tool-round ceiling.
- `source-failure` and `no-materially-repeated-assistant-turn`: provider response did not contain a message.
- The topic-switch, promotions, and agency-hours scenarios passed.

The observed free-router replies also treated fixture offer text as if it described a
real promotion. That is a test-fixture grounding defect to fix before interpreting
promotion answer quality; it was not evidence from the production offer catalog.

## Recommendation

Keep `openrouter/free` as a last-resort provider. Its one-turn tool-call compatibility
passed, but the broader suite passed only 3/8, and its router can select free models
dynamically. Keep catalog, offer, finance, appointment, and lead-flow decisions under
application/tool policy. Add no-result and tool-loop safeguards, then rerun the pinned
Qwen/Gemma comparison after rate limits clear. Do not promote a free model based on
the provider preflight alone.

These live model scores do not include Jev classification, the actual MCP tunnel, or
the deployed conversation endpoint. No persistent clean test conversation was
created, so the production database remains unchanged.
