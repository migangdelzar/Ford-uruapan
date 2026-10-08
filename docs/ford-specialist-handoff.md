# Ford specialist handoff

## Current boundary

The Ford information MCP remains read-only and is still limited by `ford.mcp.read-only-tool-names`.
Customer handoff is decided in the main conversation orchestration path. The application now
authorizes a direct customer request, or a later clear affirmative only when the immediately
preceding persisted assistant turn offered specialist help. Declines override authorization. A
model handoff flag by itself cannot create a request. Both the graph and legacy conversation paths
apply the same authorization policy.

The current in-app handoff contract is the conversation repository request plus
`HandoffRequested` application event. No external advisor, CRM, appointment, phone, or email
integration contract was found. The typed `SpecialistHandoffPayload` is limited to `reason`,
`vehicle`, `needs`, and `unresolvedQuestion`; its `arguments()` map omits absent values. It is
attached to the internal event and does not claim external contact. Operators can use the linked
conversation for any additional context.

## Prompt and live override

The source prompt is [`ford-uruapan-system.md`](../ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md).
It preserves the Ford MCP routing, exact variant and price grounding, offer validity disclosures,
and adds the handoff policy. Application authorization remains authoritative.

`SystemPromptService` prefers a persisted prompt override to this classpath seed. Check
`GET /api/v1/operator/ai/system-prompt` and update it through the authenticated operator `PUT`
route using the returned `version` as `expectedVersion`; the seed edit alone does not replace an
existing database override. The emme-2 `ai_system_prompt` table currently has no override row, and
the deployed app JAR prompt SHA-256 matches the source prompt (`e434a4ed1e0ff92a0b7b1d66a9d5119962a194459ddf155aaca308ba6c910202`). The deployed classpath prompt is therefore active. Operator prompt routes require a bearer JWT from the configured OIDC issuer.

## Runtime topology and operations

- The Ford information MCP runs on this workstation at `127.0.0.1:3001/mcp` and is exposed at
  `https://ford-mcp.emme.business/mcp` by the dedicated Cloudflare tunnel `ford-mcp-workstation`.
- The pre-existing `ford.emme.business` Cloudflare route still points to the emme-1 app on port
  8080. The new MCP route is separate and does not change that route.
- The MCP HTTP server and `cloudflared` are installed as per-user macOS LaunchAgents and restart
  automatically while the workstation user session is active. The workstation must remain awake
  and connected for emme-1 to reach the MCP. Check them with
  `launchctl print gui/501/com.ford.uruapan.mcp-http` and
  `launchctl print gui/501/com.ford.uruapan.cloudflared`.
- The existing `mise run deploy:app` Ansible flow deploys the app to emme-1 and leaves emme-2 data
  services alone. It reads secrets from the private `deployment/config/oci.env`; never print or
  commit that file.
- Current emme-1 image: `ford-service:handoff-2026-09-28-r2`. Public health returned HTTP 200 at
  `https://ford.emme.business/api/v1/health`. Ansible verified PostgreSQL and Redis connectivity;
  `mise run deploy:status` showed emme-2 PostgreSQL and Redis healthy.
- The app registered the eight configured read-only information tools from Ford MCP, including
  `search_vehicle_and_offers`. The MCP token remains in private local/deployment configuration.

## Verification

Run the application policy tests from `ford-ai-service`:

```sh
./gradlew test --tests '*CustomerHandoffAuthorizationPolicyTest' \
  --tests '*MultistepLeadHandoffFixtureTest' \
  --tests '*SpecialistHandoffPayloadFactoryTest' \
  --tests '*ConversationWorkflowTest' \
  --tests '*DefaultConversationTurnProcessorTest'
```

`src/test/resources/evals/multistep-leads/flows.json` is an unchanged snapshot of
`Ford-scraping/evals/multistep-leads/flows.json` (SHA-256
`f6119a4ea7290261a032d4d69e0d0cfa32c31c2bfb2979f5f8c73a962382640b`). The Java fixture test reports
only deterministic authorization/payload checks; it does not run an LLM, Ragas, an external advisor,
or a CRM mutation. The latest main-app Gradle build passed with 422 tests and no failures. The
Ford-scraping MCP test suite passed 212 unit tests; its lint and build passed. The root operations
contract suite passed 15/15.

The latest live Ford-scraping LLM/MCP evaluation is preserved at
`Ford-scraping/eval-results/multistep-leads/2026-09-28T05-33-06-645Z.jsonl`: 6/10 flows passed, four
failed, and two flows were incomplete. Ragas evaluated eight complete traces and left two
incomplete; goal accuracy was 3/8 (0.375), and handoff-tool precision/recall/F1 was 1.0 except for
`unavailable-offer-validity`, which scored 0.0. These are local-model/mock-handoff diagnostics, not
the app's deterministic policy result and not evidence of real advisor contact. Keep the raw trace
and failures; do not weaken checks to improve the score.

## Integration needed for live transfers

Before enabling an external transfer, obtain the advisor/CRM contract: endpoint and owner, server-side
credentials, required consent wording, required customer fields, idempotency key behavior, success
and failure response schema, and any appointment/contact guarantees. Until that exists, the app
must describe only the internal handoff request confirmed by its repository result and must not
claim an advisor was contacted or a call/appointment was booked.
