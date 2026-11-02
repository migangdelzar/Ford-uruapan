# WhatsApp Message Understanding and Intent Routing Design

**Status:** Approved; implementation not started

**Date:** 2026-09-27

## Goal

Accept supported WhatsApp message formats and reliably route the customer's
request to the existing conversation agent. Keep transport parsing separate
from semantic intent classification. Reuse the current classifier, LangGraph4j
conversation flow, Spring AI integrations, and approved provider policy rather
than introducing a competing agent or classifier.

## Current project context

The service has a `WhatsAppChannelAdapter` that extracts the first message's
ID, sender, and `text.body`. `AbstractMetaChannelAdapter` rejects empty or
non-text messages, and normalized `InboundMessage` currently has an empty
attachments list. Thus image, audio, location, and interactive WhatsApp
messages do not reach the conversation workflow today.

Semantic classification already exists in `ConversationDecisionService` and
`TypeSafeConversationDecisionAdapter`. It produces intent, lead stage and
temperature, handoff recommendation, tool family, sensitive-data level,
urgency, summary, and classifier evidence; rules are the configured fallback.
The graph processor loads the persisted inbound message and passes its text
through memory into the assistant. Extend this path; do not create a second
classifier or a transport-specific answerer.

The existing intent/tool enums cover general, inventory, vehicle details,
finance, offers, appointment, knowledge, and human request, with tool families
for vehicle search/details, offers, finance estimates, knowledge search, and
appointments. Trade-in, service/parts, dealer logistics, complaint/status, and
unsupported/out-of-scope handling need explicit consideration in the intent
taxonomy and routing contract; do not claim a tool exists until one is
registered and authorized.

## Proposed architecture

```text
Meta webhook
  -> signature verification and event filtering
  -> typed WhatsApp payload normalization (transport facts only)
  -> durable inbound receipt(s), preserving provider IDs and attachment refs
  -> bounded media interpretation when required and supported
  -> existing semantic classifier (intent, risk, lead, tool family)
  -> existing LangGraph4j flow and authorized Spring AI/MCP tools
  -> committed reply/outbox or a safe clarification
```

### 1. Transport normalization

Represent the WhatsApp message kind explicitly, independently of intent:

- text;
- image;
- audio/voice note;
- video;
- document;
- location;
- interactive/button/list reply;
- sticker or other unsupported kind.

Retain provider message ID, sender/account identity, safe structured fields,
and opaque media identifiers as needed. Do not log raw webhook bodies, media
URLs, access tokens, captions, or customer content. Continue filtering status
callbacks separately from customer messages. A webhook containing multiple
message entries must not silently drop entries: normalize and persist each
supported customer message idempotently, or report an explicit unsupported
event outcome. Provider IDs remain the deduplication key.

Location coordinates and interactive selections are structured transport
content, not free-form intent. Convert them into typed input for the existing
conversation turn; never silently turn them into an assumed dealership action.

### 2. Media interpretation

For audio and images, resolve the provider media reference through the
configured Meta integration, validate content type and configured size limits,
and pass bytes only to a currently approved provider whose declared
capabilities support that media type. Do not add a provider or broaden the
customer-data allow-list as part of this feature. Keep credentials and media
URLs out of logs. Bound download and model time, clean temporary media after
processing, and persist only the inbound record plus the minimum useful
transcription/description needed for the conversation and audit policy.

Do not assume all configured models support vision or audio. Capability
selection must be explicit and fail closed. If retrieval, interpretation,
validation, or provider capability fails, preserve the original inbound turn
and ask the customer to describe the content or send text. Never fabricate
what an image or recording contains.

Video, documents, stickers, and other unsupported kinds receive a concise,
channel-appropriate request for text or a supported attachment. This first
iteration does not add OCR/document extraction or video analysis.

### 3. Semantic request classification and routing

After text extraction/normalization, use the existing `ConversationDecision`
pipeline once per customer turn. Evolve its schema and rules/model questions
only as required to distinguish at least:

- greeting/chitchat/general and clarification;
- vehicle discovery and vehicle details;
- current offers/promotions;
- financing/payment estimate;
- trade-in;
- test drive/appointment;
- dealership information and logistics;
- service/parts/support request;
- complaint/status request;
- explicit human request;
- unsupported/out-of-scope or unsafe request.

Keep lead temperature/stage and next action as a separate business axis, not
synonyms for user intent. Keep sensitive-data/risk signals separate from both.
The classifier selects intent and tool family; authorization remains
deterministic and tenant-scoped. The graph/model may answer directly or call an
authorized tool. If confidence is insufficient, intent/tool-family conflict is
present, or a required tool is unavailable, ask one focused clarification or
give a transparent limitation response. Never invent tool results or current
offers. Unsupported intent categories must not map to an unrelated tool.

Use the configured classifier and current rules fallback. Validate classifier
output against the known schema and enum values. Record category, confidence,
fallback, chosen tool family, and processing outcome without logging message
content. Keep semantic category evidence available for diagnostics and future
evaluation.

## Error and privacy behavior

| Condition | Behavior |
|---|---|
| Invalid signature or malformed webhook | Preserve existing rejection behavior; do not process content |
| Status callback or non-customer event | Ignore/acknowledge per existing adapter contract |
| Supported text/structured message | Persist idempotently and continue through the existing graph |
| Media unavailable, too large, invalid type, or unsupported by approved model | Keep the turn; ask the customer to describe it in text |
| Classifier/provider failure or invalid output | Use existing rules fallback; if still ambiguous, ask a concise clarification |
| Intent requests a tool not authorized/registered | Do not call it; explain limitation or clarify |
| Duplicate provider message ID | Do not create another turn or outbound reply |

Apply tenant isolation and existing sensitive-data redaction/block rules before
external model calls. Metrics/log fields may include conversation/receipt IDs,
transport kind, intent, tool family, confidence band, provider/model identity,
latencies, fallback/error category, and reply/outbox status. Never include raw
message, transcript, prompt, attachment URL, media bytes, tokens, or secrets.

## Alternatives considered

1. **Text-only with attachment prompts:** simplest and lowest external data
   exposure, but fails the requirement to understand voice notes and images.
2. **Typed parsing plus approved-capability media interpretation (selected):**
   useful for common WhatsApp media while retaining explicit provider and data
   controls; costs latency and requires secure media retrieval/cleanup.
3. **Send every payload directly to a general multimodal agent:** fewer explicit
   stages, but mixes transport facts with model inference, broadens data
   exposure, complicates deterministic validation, and is harder to diagnose.

## Scope and non-goals

In scope: WhatsApp transport type normalization; idempotent handling of
multiple inbound messages in one webhook; audio/image interpretation through
already approved capable providers; safe unsupported-media response; extending
existing semantic intent/routing taxonomy; tests and useful non-content
observability.

Out of scope: adding providers or changing their approval; OCR/document or
video understanding; changing Ford MCP/RAG ingestion or data; redesigning lead
handoff policy; replacing LangGraph4j, Spring AI, or the existing classifier;
resetting production data.

## Verification strategy

- Adapter contract tests for every supported message kind, malformed payloads,
  status callbacks, multiple message entries, missing identifiers, and provider
  message deduplication.
- Unit tests for media capability/size/type gating, safe fallback, temporary
  media cleanup, and ensuring unapproved providers never receive media.
- Classifier tests for every intent category, low confidence, unknown labels,
  rules fallback, risk separation, and authorized-tool-family mapping.
- Integration tests from Meta payload through durable receipt, graph routing,
  and exactly one outbound result; include unsupported-media clarification.
- Verify logs/metrics contain identifiers and classifications but no message,
  transcript, prompt, media URL, or credential.
- Run relevant unit, integration, and build checks before any deployment. No
  database reset is part of rollout.

## Open implementation details

Before implementation, verify resolved Meta Graph API version, current media
download client/auth boundary, approved providers' actual audio/image support,
inbound storage schema for typed payload and extracted text, and current
webhook-to-receipt transaction semantics. Use the supported APIs from the
project's resolved dependency versions; do not infer model media support from
provider names.
