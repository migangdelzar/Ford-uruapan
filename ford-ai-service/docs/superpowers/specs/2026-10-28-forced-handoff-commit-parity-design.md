# Forced Handoff Commit Parity Design

## Problem

Conversation handoff is committed through two code paths: ordinary assistant-requested handoff in `ConversationTurnCommitService.commit` and forced policy handoff in `forceHumanHandoff`. The duplicated forced path has drifted: it omitted staged alternate contacts, decision evidence, turn-ledger state, and decision trace records, and it used a reason that did not match its sanitized notification payload. These gaps break operational visibility and privacy-preserving handoff behavior.

## Goals

- Give every accepted handoff the same durable state and observability guarantees, regardless of which routing policy selected it.
- Preserve only sanitized customer context in forced handoff notifications and tickets.
- Carry an actual assistant decision through when one exists; do not invent model evidence when handoff occurs before assistant execution.
- Preserve existing idempotency, optimistic-lock, handoff reason, priority, and outbox/event behavior.

## Design

Extract a shared handoff-commit helper inside `ConversationTurnCommitService`. Both normal and forced routes provide the helper with their handoff decision, customer-safe payload, packet, internal note, optional real conversation decision, and any available assistant trace. The helper owns the state-changing request and all accepted-handoff effects: decision persistence when supplied, `HUMAN_PENDING` ledger outcome, handoff trace append, notification publication, and staged-contact cleanup. A rejected CAS remains a duplicate and produces no accepted-handoff effects.

The normal path supplies its actual assistant decision and trace. The forced path is selected before assistant execution and supplies no model decision or model trace. Both paths use the same sanitized payload reason for the handoff decision and notification. Alternate contacts are carried only from the encrypted staging flow into the handoff packet and are deleted after successful handoff.

Routing stays in the server-owned `AdvisorResponseDispositionPolicy`, separate from commit mechanics. Criticality wins: explicit human requests, serious complaints, sensitive documents, and repeated substantive failures take the mandatory-handoff route even if an answer could otherwise be reviewed. Routine uncertainty, missing or conflicting evidence, unsupported commitments, and response-review signals take the advisor-approval route; supported, validated routine responses remain automatic. Confidence is system-grounded in evidence and quality outcomes. The model's self-reported confidence is not a standalone routing signal, and this change adds no numeric threshold or new criticality category.

## Error Handling and Privacy

All writes and event publication remain within the existing transaction boundary. Preserve request idempotency and optimistic-lock behavior. Do not weaken safety gates, invoke alternate conversation graphs, expose staged contact in public ticket views, or fabricate tool/model evidence. A handoff rejected by CAS returns `DUPLICATE` without publishing a handoff notification or recording a successful ledger outcome.

## Verification

- Unit tests verify both ordinary and forced handoffs share reason, decision, trace, ledger, event, idempotency, and duplicate semantics.
- Policy tests verify mandatory criticality takes precedence over advisor review, while routine evidence uncertainty stays reviewable and safe verified responses stay automatic.
- PostgreSQL integration tests verify forced handoff through the conversation processor retains consented alternate contact only in the private ticket packet, releases encrypted staging, writes the `HUMAN_PENDING` turn and trace, and does not leak contact data to public or persisted text surfaces.
- Approval integration coverage remains unchanged; fresh all-live Qwen acceptance is a separate gate and must still pass before catalog import.

## Scope

This change is limited to handoff commit parity in the existing production conversation graph. It does not change advisor approval semantics, model prompts, catalog data, or webhook routing contracts.
