# Jev Classification and Free-Model Orchestration Design

## Status

Approved design pending implementation.

## Goal

Use TypeSafe Jev through OpenRouter to classify each eligible conversation turn, then pass the validated classification bundle into the normal response-generation model. The generation model may be a free OpenRouter model, while Java remains responsible for authorization, tool access, persistence, handoff policy, and delivery.

The feature must support the existing WhatsApp webhook, durable conversation memory, read-only dealership tools, outbox delivery, and the current bot-only deployment mode.

## Scope

The first implementation covers five logical classifiers:

- intent;
- lead stage and temperature;
- handoff recommendation;
- tool capability/family;
- sensitive-data level.

Jev is the active classifier provider. A deterministic rules implementation remains available as a per-classifier fallback. The response generator remains a separate provider and receives only normalized, validated decision data.

This design does not allow an AI provider to directly send messages, mutate business state, authorize tools, or create a handoff.

## Non-goals

- Replacing the normal chat model with Jev for prose generation.
- Allowing Jev to execute tools or make authorization decisions.
- Making the frontend call OpenRouter directly.
- Storing raw Jev prompts or provider responses by default.
- Introducing separate models for every classifier in the first release.
- Making free-model availability a hard dependency without a fallback path.

## Architecture

The application layer introduces a `ConversationDecisionService` that coordinates typed classifier ports:

```text
ConversationDecisionService
  ├── IntentClassifier
  ├── LeadClassifier
  ├── HandoffClassifier
  ├── ToolCapabilityClassifier
  └── SensitiveDataClassifier
```

Each port has a Jev adapter and a deterministic fallback adapter. The service may batch compatible classifier questions into one Jev decision request or execute independent requests in parallel. The application-facing contract is unchanged by that optimization.

The existing keyword `IntentRouter` becomes the deterministic intent fallback. It must not be removed until the Jev path has proven stable.

The conversation workflow becomes:

```text
Inbound webhook
  → validate signature, binding, tenant, and deduplication
  → persist inbound message
  → load recent conversation memory
  → ConversationDecisionService
  → DecisionPolicy
  → normal response-generation model with decision context and allowed tools
  → persist outbound message
  → outbox/Meta delivery
```

Classification runs after durable inbound acceptance so provider failures do not lose the message. It runs outside the webhook's short acknowledgement path.

## Typed decision contract

The normalized decision bundle uses bounded values:

```text
Intent:
  GENERAL, INVENTORY, VEHICLE_DETAILS, FINANCE,
  OFFERS, APPOINTMENT, KNOWLEDGE, HUMAN_REQUEST

LeadTemperature:
  UNKNOWN, COLD, WARM, HOT

LeadStage:
  NEW, QUALIFYING, QUALIFIED, CONVERTED, LOST

HandoffRecommendation:
  NONE, USER_REQUESTED, NEGATIVE_SENTIMENT,
  LOW_CONFIDENCE, COMPLEX_REQUEST

ToolFamily:
  NONE, VEHICLE_SEARCH, VEHICLE_DETAILS,
  FINANCE_ESTIMATE, KNOWLEDGE_SEARCH, APPOINTMENT

SensitiveDataLevel:
  NONE, POSSIBLE, CONFIRMED
```

The application contract is equivalent to:

```text
ConversationDecision
  intent
  leadTemperature
  leadStage
  handoffRecommendation
  toolFamily
  sensitiveDataLevel
  evidence per classifier
```

Each evidence entry includes classification, confidence, reason code, provider, model, latency, and whether fallback was used. Provider output is parsed and validated against the enum contract. Unknown labels, malformed payloads, and missing required values become fallback results rather than unchecked values.

## Decision policy

The `DecisionPolicy` is authoritative and runs after classification:

- `FORD_HUMAN_HANDOFF_ENABLED=false` always prevents handoff, regardless of Jev output.
- A handoff recommendation is not a handoff command.
- Explicit user requests for a human may be recognized deterministically, but the feature flag still controls execution.
- Tool family is mapped to an allowlist of existing tool callbacks.
- `ToolAuthorizationPolicy` remains the final validation for every actual tool call.
- Sensitive-data classification may restrict cloud generation and tools according to configured policy.
- Low-confidence results use classifier-specific fallback behavior.
- The model receives recommendations as context, not as authority.

In bot-only mode, a handoff recommendation is persisted for diagnostics but the conversation remains bot-active.

## Passing decisions to the generation model

The normal chat model receives a compact, sanitized decision context containing only validated fields, for example:

```text
intent=FINANCE
lead_stage=QUALIFYING
lead_temperature=WARM
tool_family=FINANCE_ESTIMATE
handoff=NONE
sensitive_data=NONE
```

The generator may use this context to answer in the correct mode and request an appropriate tool. The application still filters tools before invocation and validates tool arguments after invocation. Raw Jev explanations, prompts, and provider metadata are not sent to the customer-facing model unless explicitly required by a future design.

## OpenRouter and model configuration

Jev and response generation are separate provider roles:

```properties
FORD_AI_CLASSIFICATION_ENABLED=true
FORD_AI_CLASSIFICATION_MODE=active
FORD_AI_CLASSIFICATION_PROVIDER=jev-openrouter
FORD_AI_CLASSIFICATION_MODEL=~typesafe/jev-latest
FORD_AI_CLASSIFICATION_ENDPOINT=https://openrouter.ai/api
FORD_AI_CLASSIFICATION_API_KEY=${OPENROUTER_API_KEY}
FORD_AI_CLASSIFICATION_TIMEOUT_MS=1500
FORD_AI_CLASSIFICATION_MIN_CONFIDENCE=0.70
FORD_AI_CLASSIFICATION_FALLBACK=rules
FORD_AI_CLASSIFICATION_PII_POLICY=redact

FORD_AI_CHAT_PROVIDER=openai
FORD_AI_CLOUD_PROVIDER=openai
FORD_AI_CLOUD_ENABLED=true
FORD_AI_CLOUD_BASE_URL=https://openrouter.ai/api/v1
FORD_AI_CLOUD_MODEL_VERSION=google/gemma-4-26b-a4b-it:free
FORD_AI_CLOUD_FALLBACK_MODEL_VERSION=openrouter/free
OPENAI_API_KEY=${OPENROUTER_API_KEY}
```

The actual free generation model remains deployment configuration, not Java code. This is necessary because free-model availability, quotas, context limits, and tool-calling support can change. The provider capability registry must reject a selected model that cannot support the requested tool or structured-output behavior, or route to a compatible fallback.

The Jev adapter isolates the provider-specific decision API from the OpenAI-compatible chat API. The endpoint, authentication, request envelope, and response parser must remain inside that adapter so a provider API change does not leak into application code.

## Classifier controls

Each logical classifier can be enabled independently:

```properties
FORD_AI_CLASSIFIER_INTENT_ENABLED=true
FORD_AI_CLASSIFIER_LEAD_ENABLED=true
FORD_AI_CLASSIFIER_HANDOFF_ENABLED=true
FORD_AI_CLASSIFIER_TOOLS_ENABLED=true
FORD_AI_CLASSIFIER_SENSITIVE_DATA_ENABLED=true
```

The initial active configuration enables all five classifiers. A future deployment can disable a classifier without disabling the rest.

## Failure handling

Failures are isolated by classifier:

```text
intent failure          → existing IntentRouter fallback
lead failure            → UNKNOWN / unchanged lead state
handoff failure         → NONE
tool-family failure     → NONE
sensitive-data failure  → most restrictive configured policy
```

Rate limits, timeouts, HTTP errors, invalid decision payloads, and unsupported model capabilities are recorded as provider failures. They must not leave the database transaction aborted or prevent durable inbound-message persistence.

If classification is unavailable, response generation may continue using the fallback decision bundle when configured. If the free generation model is unavailable, the provider routing policy may use its configured fallback or produce the existing safe failure response. No automatic handoff is created solely because a provider failed.

## Persistence and observability

Persist one normalized row per classifier decision in `conversation_decision` with:

```text
id
conversation_id
message_id
classifier_type
classification
confidence
reason_code
provider
model
fallback_used
created_at
```

Do not persist raw prompts, raw customer messages, or raw provider responses in this table. Existing message and conversation tables remain the source of conversation history.

Operational logs and metrics include classifier type, outcome, confidence bucket, provider, model, latency, fallback usage, and failure category. Customer content and API keys are never logged.

## Testing strategy

- Unit-test each classifier contract with valid, unknown, malformed, low-confidence, timeout, and provider-error results.
- Test `DecisionPolicy` independently, especially bot-only handoff behavior and tool allowlists.
- Test Jev adapter parsing with recorded sanitized provider fixtures.
- Test provider routing when the configured generation model is free, rate-limited, unavailable, or lacks tool support.
- Test conversation workflow persistence when classification or generation fails.
- Test duplicate webhook delivery remains idempotent.
- Add an integration test proving decision context reaches the generation port while raw provider output does not.
- Add an end-to-end test verifying a WhatsApp message is persisted, classified, answered, and placed in the outbox.

## Rollout

The target deployment enables Jev actively, but the configuration retains per-classifier switches and rules fallbacks. A safe operational sequence is:

1. Enable Jev classification with the configured free generation model.
2. Verify classifier and generation provider capability diagnostics at startup.
3. Send test messages for each intent and lead temperature.
4. Confirm the decision rows and generated responses in PostgreSQL.
5. Confirm bot-only mode never creates a handoff ticket.
6. Monitor provider rate limits and fallback usage before adding additional free models.
