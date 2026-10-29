## 2026-10-26 — Task 1: Exact Buyer Vehicle Resolution

### Subtitle: Implement replay-only exact catalog matching using current and buyer-history lines

### Task: Resolver test-first implementation

- [x] Added behavior tests, confirmed missing resolver compile failure, then implemented and verified
  - **Actions Applied**
    - `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/LocalBuyerVehicleResolverTest.java` — added seven tests for history buyer identity, current-message priority, assistant exclusion, ambiguity, empty input, normalization, and no partial trim match.
    - `ford-ai-service/src/integrationTest/java/com/ford/service/conversations/LocalBuyerVehicleResolver.java` — implemented normalized exact catalog identity matching; current-message ambiguity fails closed; history limited to buyer lines within history block, scanned newest-first, with first matching line ambiguity failing closed.
  - **Verification**
    - RED: `./gradlew integrationTest --tests com.ford.service.conversations.LocalBuyerVehicleResolverTest` — failed at `:compileIntegrationTestJava` because `LocalBuyerVehicleResolver` was not yet implemented (`cannot find symbol`).
    - GREEN: `./gradlew integrationTest --tests com.ford.service.conversations.LocalBuyerVehicleResolverTest` — `BUILD SUCCESSFUL`; seven tests completed, zero failures.
    - Commit: `2eabd20583deb8165730e0e5b39ab53f2aa82838` (`Add buyer-context exact vehicle resolver`). Only the two Task 1 files were staged and committed.

### Summary — Current Status

Task 1 implemented and focused tests pass. No assistant identity, session summary, semantic candidate, or partial trim match is used.

### Next Up

Proceed with the next approved plan task.

### Self-review

- Scope limited to the two Task 1 files; reusable `VehicleNameIndexEntry` remains untouched.
- Current-message resolution happens before any history parsing; multiple exact current-message identities return empty immediately.
- History parsing requires the explicit `[HISTORIAL DE CONVERSACIÓN]` block and captures only `- usuario:` lines. The nearest buyer line containing one or more identities controls the result; ambiguous line returns empty.
- Repeated whitespace and case normalize consistently; matching is complete catalog model/year/trim text with token boundaries.
- Test output includes Gradle's existing deprecation/configuration-cache notices; no test warnings or failures.
