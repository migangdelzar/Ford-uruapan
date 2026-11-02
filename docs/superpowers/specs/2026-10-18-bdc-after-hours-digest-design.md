# BDC After-Hours Digest Design

**Status:** Approved for implementation
**Date:** 2026-10-18
**Scope:** Ford Service operator API and Ford-web local CRM inbox
**Approval:** User selected an authenticated Ford-web in-app digest only; the user approved the read-only Service query + Ford-web BFF/card design.

## Objective

Make after-hours handoffs visible to the BDC in Ford-web by 08:45 on the next dealership opening day. The existing Service handoff queue remains the source of truth. The digest is a read-only, tenant-scoped view over that queue; it does not create a second CRM record or deliver an external notification.

## Decisions

- Delivery is in-app only in Ford-web. No email, WhatsApp, Slack, push notification, or external CRM provider is added.
- Ford Service computes the digest window from the configured dealership schedule in `America/Mexico_City`, including explicit special-hours/date overrides. It does not invent national holidays.
- The digest becomes available at 08:45 local time on the selected opening date. Before then, the API returns the scheduled availability time and no ticket details.
- After 08:45, the digest covers after-hours handoff tickets created after the previous open interval closed and before today's opening time. On an opening day, the card can include a ticket created between 08:45 and opening time when the operator reloads it.
- On a closed day or after the dealership closes, the selected digest date is the next configured opening date.
- A ticket is included only if its creation instant was outside configured open hours. The digest includes ticket ID, conversation ID, creation time, status, reason, and packet completeness/partial status. The operator follows the conversation link to inspect the existing authorized packet; the digest does not duplicate personal fields.
- Direct human requests, complaints, or refusals can create intentionally partial packets. The card must show completeness and must not imply that every packet is complete.
- Tenant authorization comes from the authenticated operator principal. A caller may request only a tenant in that principal's claims.
- The endpoint is safe to reload and does not mutate tickets, mark them read, claim them, or persist duplicate digest snapshots.

## Components and flow

1. Ford Service's `DealershipHoursPolicy` determines the next digest date, 08:45 availability instant, prior opening-day close, and current opening boundary from the configured schedule and `Clock`.
2. A read-only query in the handoff repository returns tickets for the authorized tenant within that bounded interval. The application service filters out tickets created during open hours and returns packet completeness metadata.
3. An authenticated operator endpoint returns a typed digest response. Before availability it returns metadata only; after availability it includes the matching ticket summaries.
4. Ford-web calls the endpoint through its existing same-origin authenticated BFF proxy and displays a dated digest card in the CRM inbox. The card links to each conversation and displays partial packet status.

The response contains `tenantId`, `businessDate`, `timeZone`, `available`, `availableAt`, `windowStart`, `windowEnd`, `totalCount`, `completePacketCount`, `partialPacketCount`, and `items`. Each item contains `handoffId`, `conversationId`, `createdAt`, `status`, `reason`, `packetCompleteness`, and `packetPartial`. It deliberately omits packet values such as customer name, phone, budget, and down payment; operators open the existing conversation/handoff view to inspect authorized details.

## Failure behavior

- Missing/invalid tenant access returns the existing authorization failure; no cross-tenant aggregation occurs.
- Invalid/absent schedule configuration fails closed through the existing `DealershipHoursPolicy` configuration validation.
- If no opening interval can be found within the existing policy horizon, return a service error and render the inbox's normal retry/error state. Do not substitute a guessed holiday calendar.
- Service/BFF failure displays a retryable digest error and does not hide the rest of the inbox.
- Empty digest returns a valid zero-count response after 08:45.

## Acceptance criteria

- Fixed-clock tests cover weekday, Saturday, Sunday, explicit closed-day override, special hours, before/at/after 08:45, after-close selection, and prior-close window boundaries.
- Repository and controller tests prove tenant isolation, bounded date queries, no mutation, hidden ticket details before 08:45, inclusion only for after-hours tickets, and correct partial-packet counts.
- Ford-web tests prove the card renders the date/count/empty state, marks partial packets, links to the conversation, and supports retry without blocking the inbox.
- OpenAPI and BFF/API contracts describe the same response.
- Existing Service and Ford-web tests/builds pass.
- Production launch remains gated on approved dealership hours/holiday overrides, operator dry-run, protected release, and live verification; local test results do not prove the 08:45 production SLA.

## Scope boundary

This implements the approved in-app digest decision. It does not implement external CRM contact/deal creation, external alerts, a background scheduler, or a second durable digest table. The original draft's request for a complete packet on every after-hours conversation is represented honestly: existing handoff rules permit partial packets on early human requests/complaints/refusals, and the digest makes that status visible.
