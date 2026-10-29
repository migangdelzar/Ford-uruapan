package com.ford.service.conversations;

/** Guard for the explicitly no-handoff prefetched memory benchmark. */
final class LocalReplayNoHandoffAssertions {
    private LocalReplayNoHandoffAssertions() { }

    static void assertNoHandoff(int handoffCount) {
        if (handoffCount != 0) {
            throw new AssertionError("no-handoff memory replay detected " + handoffCount + " handoff(s)");
        }
    }
}
