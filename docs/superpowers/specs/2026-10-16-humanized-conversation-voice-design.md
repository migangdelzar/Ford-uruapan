# Humanized Ford Conversation Voice Design

**Status:** Implemented, merged, and deployed as Ford Service `v0.1.23`; authenticated production conversation and rollback-image verification pending
**Date:** 2026-10-16
**Scope:** Existing Ford Service customer-facing conversation replies

## Context

The reviewed CSV describes MÍA as Ford Uruapan's virtual assistant and selects a
formal, corporate style. The user confirmed formal **usted**, allowed verified
catalog/list prices, reserved final quotes and negotiation for an advisor, and
asked to preserve the existing new-vehicle and promotion behavior.

The existing system prompt already covers Mexican Spanish, concise answers, one
question per turn, memory-aware follow-ups, progressive sales qualification,
MCP-backed vehicle facts and promotions, and careful advisor handoff claims.
The remaining mismatch is in application-owned behavior: first-contact names
rotate, and some deterministic fallback, handoff, and test-drive messages use
informal **tú** forms. A database-stored prompt can also override the classpath
prompt, so editing only the prompt file cannot enforce one voice in production.

## Design

Keep the current conversation architecture, lead data, tool integrations, and
business rules. Make the current voice consistent across the model and every
application-owned customer reply:

- Use the fixed assistant identity **MÍA** and introduce her once as Ford
  Uruapan's virtual assistant.
- Use formal **usted** in generated-response instructions and all existing
  customer-facing deterministic messages, including security/quality recovery,
  scope and provider fallbacks, human-active replies, advisor takeover, and
  pending test-drive replies.
- Add a concise code-owned persona/style rule after the runtime-editable prompt
  in every model attempt, including response repair. This preserves the
  operator prompt feature while preventing saved prompt text from changing the
  assistant identity or address style.
- Keep the source prompt aligned with the same voice and retain its existing
  price/promotion rules: communicate only current MCP-verified published prices
  and terms; leave final quotes and negotiation to an advisor.
- Keep current lead qualification, hours, handoff authorization, memory,
  fallback safety, MCP tool policy, and public channel behavior unchanged.

## Alternatives considered

1. **Edit the classpath prompt only.** Smallest diff, but saved prompt overrides
   and deterministic application messages can still produce inconsistent copy.
2. **Apply the focused voice rule across current response paths (selected).**
   Small changes at existing ownership points; keeps runtime prompt editing but
   ensures the chosen identity and tone survive overrides and fallback paths.
3. **Expand the CSV into new lead fields, CRM, service, or business-hour flows.**
   Rejected because the user asked to keep existing behavior and verify what is
   already implemented before introducing new processes.

## Failure behavior and safety

Provider failures continue through the current safe fallback. Exact prices,
specifications, promotions, eligibility, and availability remain grounded in
authorized current evidence. If generation fails, the response remains concise,
formal, and honest about the limit. A handoff message only describes the actual
in-app operator takeover; it does not promise a reply time or claim an external
advisor was contacted.

## Verification

- Unit tests prove the fixed MÍA identity and one-time introduction.
- Unit tests cover formal address in scope, security, provider/quality fallback,
  human-active, advisor takeover, and test-drive response copy.
- Adapter tests prove the code-owned voice rule is included for initial and
  repair model attempts even when the database prompt differs from the seed.
- Prompt/evaluation coverage confirms the existing new-vehicle discovery,
  one-question limit, verified catalog price/promotion behavior, and final-quote
  boundary remain in place.
- Run focused Gradle tests, the full service test/check suite, deterministic AI
  evaluation, container smoke/scan, and the existing protected production
  release workflow. Verify the deployed version and health before reporting
  production complete.

## Rollout

Merge the implementation through the repository's normal protected `main`
workflow. Release only the successful `main` source through the existing
blue-green GitHub Actions deployment. Preserve the current rollback image and
validate health plus a synthetic conversation using the existing live channels
and MCP setup. Do not delete or reset conversation data.
