# Ford specialist handoff

## Current boundary

Catalog facts come from Spring AI in-process, read-only catalog tools backed by PostgreSQL read views. The retired remote catalog protocol is not part of the current Service request path. Production runtime cleanup remains tracked in [`tasks/todo.md`](../tasks/todo.md).

Customer handoff is decided in the main conversation orchestration path. The application authorizes a direct customer request, or a later clear affirmative only when the immediately preceding persisted assistant turn offered specialist help. Declines override authorization. A model handoff flag by itself cannot create a request. Both graph and legacy conversation paths apply the same authorization policy.

The current in-app handoff contract is the conversation repository request plus `HandoffRequested` application event. No external advisor, CRM, appointment, phone, or email integration contract is configured. The typed handoff payload is limited to `reason`, `vehicle`, `needs`, and `unresolvedQuestion`; it does not claim external contact.

## Prompt and live override

The source prompt is [`ford-uruapan-system.md`](../ford-ai-service/src/main/resources/prompts/ford-uruapan-system.md). It describes current catalog tools, exact variant and price grounding, offer validity disclosures, and handoff policy. Application authorization remains authoritative.

`SystemPromptService` prefers a persisted prompt override to this classpath seed. Check `GET /api/v1/operator/ai/system-prompt` and update it through the authenticated operator `PUT` route using the returned `version` as `expectedVersion`; a seed edit alone does not replace an existing database override.

## Verification

Run the application policy tests from `ford-ai-service`:

```sh
./gradlew test --tests '*CustomerHandoffAuthorizationPolicyTest' \
  --tests '*MultistepLeadHandoffFixtureTest' \
  --tests '*SpecialistHandoffPayloadFactoryTest' \
  --tests '*ConversationWorkflowTest' \
  --tests '*DefaultConversationTurnProcessorTest'
```

The fixture tests check deterministic authorization and payload behavior. They do not run a live model, create CRM records, or confirm advisor contact.

## Integration needed for live transfers

Before enabling an external transfer, obtain the advisor/CRM contract: endpoint and owner, server-side credentials, consent wording, required customer fields, idempotency behavior, success/failure schema, and any appointment/contact guarantees. Until then, describe only the internal handoff request confirmed by the repository result.
