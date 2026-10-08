# Jev Spanish Smoke Evaluation

Date: 2026-10-08

Model: Jev 1.13 (`jev-latest`)

Input: eight synthetic Mexican Spanish dealership messages; no customer data

## Results

| Flow | Jev intent | Jev tool family | Expected intent/tool |
|---|---|---|---|
| Inventory availability | `INVENTORY` | `VEHICLE_SEARCH` | Match |
| Vehicle fuel economy | `VEHICLE_DETAILS` | `VEHICLE_DETAILS` | Match |
| Financing plans | `FINANCE` | `FINANCE_ESTIMATE` | Match |
| Advisor request and test drive | `HUMAN_REQUEST` | `APPOINTMENT` | Match; explicit person request detected |
| Complaint follow-up | `COMPLAINT_STATUS` | `NONE` | Match |
| Dealership hours | `DEALERSHIP_INFO` | `KNOWLEDGE_SEARCH` | Match |
| Unrelated weather request | `OUT_OF_SCOPE` | `KNOWLEDGE_SEARCH` | Intent match; raw Jev tool choice mismatched |
| Greeting | `GENERAL` | `NONE` | Match |

Jev matched all eight expected intents and seven of eight raw intent/tool pairs.
For the unrelated request, the deterministic classifier says `OUT_OF_SCOPE` and
`NONE`; the app now forces that deterministic route so Jev cannot leave a tool
enabled for general or out-of-scope traffic. Deterministic specific routes also
correct conflicting Jev choices for inventory, vehicle details, finance,
offers, appointments, and service support. When rules only produce a general
knowledge route, Jev can retain a more specific vehicle-details decision.

The eight requests used 7,689 input tokens. At the published $0.042 per million
input tokens, the evaluation cost was approximately $0.000323. Actual app
requests include lead-stage, lead-temperature, handoff, and tool questions and
may include sanitized memory context, so production usage per turn can differ.

## Limits

This is a small synthetic smoke check, not a statistically meaningful
Spanish-language accuracy evaluation. TypeSafe says English performs best and
recommends testing other languages on representative data. Review real usage
and labeled Spanish conversations before treating Jev as a trusted sales
classifier. The rules fallback and deterministic scope/handoff policies remain
authoritative for their respective safety decisions.
