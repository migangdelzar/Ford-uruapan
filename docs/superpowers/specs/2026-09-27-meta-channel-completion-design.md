# Meta Messaging Channel Completion Design

**Status:** Approved for implementation by the user on 2026-09-27  
**Scope:** Restore the merged application's build, then make the shared WhatsApp, Messenger, and Instagram text-conversation path complete and safe.

## Context

The application already has a hexagonal channel boundary: `ChannelAdapter` normalizes inbound provider payloads, `InboundWebhookService` resolves channel bindings, and `ChannelDeliveryPort` implementations dispatch outbox replies. Messenger and Instagram adapters and delivery configuration exist, but the inbound adapters currently normalize only the first message in a payload and don't distinguish user messages from provider echoes/status events. Binding initialization is WhatsApp-only. The delivery transport shares one non-WhatsApp payload for Messenger and Instagram, and outbox delivery doesn't check the existing customer window before sending. A merged Spring configuration also references a missing `ObservedToolCallback`, so Java compilation currently fails.

Meta's official collections describe separate Messenger Page and Instagram professional-account send surfaces and their respective permissions; both limit replies to an eligible customer-initiated conversation window. The implementation must retain separate channel credentials/endpoints and never infer permission to message outside provider policy. Sources: [Meta Messenger Send API](https://www.postman.com/meta/messenger-platform-api/folder/vilwbh4/send-api), [Meta Instagram Send API](https://www.postman.com/meta/instagram/folder/uxudqu0/send-api), [Meta Instagram text-message request](https://www.postman.com/meta/instagram/request/scob1z4/text-message).

## Design

Keep one conversation/agent pipeline. Apply the existing Adapter pattern at the provider boundary and the existing Strategy selection by `Channel` at parsing and delivery boundaries. Keep the application core unaware of Graph API payload details. Do not add a generic provider framework, factory hierarchy, separate graph per channel, or new broker.

Inbound processing will verify the shared Meta signature once, identify every provider account represented by message-bearing entries, resolve each account through the existing tenant-scoped binding service, and normalize all supported customer messages for that account. Echoes, delivery/read receipts, and other non-message callbacks will be acknowledged but not treated as customer turns. Message IDs remain deduplicated using the existing tenant + channel + provider-message key. A webhook containing entries for multiple accounts must never reuse the first account's binding for the rest.

WhatsApp, Messenger, and Instagram will each have explicit payload parsing and text delivery behavior. Messenger and Instagram parse text and interactive user replies; supported location/text-like events normalize to existing `InboundMessage` kinds. Unsupported media/event payloads are acknowledged safely and routed to the existing bounded clarification path; raw provider URLs and tokens are not added to logs or model context. Existing WhatsApp image/audio retrieval remains unchanged in this scope.

Auto-binding becomes opt-in per channel while retaining the current WhatsApp environment variables and tenant ID behavior. Outbound endpoint and access token remain separate per channel. The outbox router checks the existing tenant-scoped 24-hour customer window before dispatching a text reply; template messages remain on their separate explicit path. The outbox continues to own retries and deduplication; the Meta adapter classifies provider errors without logging message text or credentials. Replies remain customer-initiated, text-only responses; out-of-window templates, comment/mention/story workflows, account OAuth onboarding, media URL retrieval for Messenger/Instagram, and cross-channel identity linking are not part of this change.

The missing `ObservedToolCallback` is a small Spring AI decorator. It delegates callback definition, metadata, and invocation unchanged while recording tool name, safe tenant/conversation identifiers when present, outcome, and duration. It must not log tool arguments or results.

## Verification

- Unit tests cover callback delegation and sanitized observation, account-ID extraction, batches, echo/status suppression, tenant/account binding per entry, all three outbound payload shapes, configured credentials/endpoints, and safe error classification.
- Controller/service tests verify webhook verification and accepted/ignored behavior without requiring Meta credentials.
- Run the complete Gradle unit, integration, and check tasks. Provider-console setup, credentials, app review, and live delivery cannot be verified locally and must be recorded as operational prerequisites.
- No production DB reset, secret value, external deployment, or push is part of this task.
