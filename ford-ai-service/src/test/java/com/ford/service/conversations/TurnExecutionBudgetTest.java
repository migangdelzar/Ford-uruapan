package com.ford.service.conversations;

import com.ford.service.conversations.application.execution.ExecutionBudgetRejectedException;
import com.ford.service.conversations.application.execution.TurnExecutionBudget;
import com.ford.service.conversations.application.execution.TurnExecutionPolicy;
import com.ford.service.conversations.application.graph.ConversationTurnReference;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnExecutionBudgetTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final TurnExecutionPolicy POLICY = TurnExecutionPolicy.approvedInitial();
    private static final TurnExecutionBudget.Fence FENCE = new TurnExecutionBudget.Fence(1, "owner");
    private static final ConversationTurnReference REF = new ConversationTurnReference(id(1), id(2), id(3));

    @Test void exposesApprovedProfile() {
        assertThat(POLICY.deadline()).isEqualTo(Duration.ofSeconds(45));
        assertThat(POLICY.providerAttemptLimit()).isEqualTo(4);
        assertThat(POLICY.toolCallLimit()).isEqualTo(6);
        assertThat(POLICY.repairLimit()).isEqualTo(1);
    }

    @Test void enforcesProviderAndToolLimitsAndReportsNewReservations() {
        var budget = start(POLICY);
        for (int i = 0; i < 4; i++) budget = reserve(budget, TurnExecutionBudget.ReservationKind.PROVIDER, id(10 + i)).budget();
        var finalBudget = budget;
        assertThatThrownBy(() -> reserve(finalBudget, TurnExecutionBudget.ReservationKind.PROVIDER, id(20)))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.LIMIT_EXCEEDED));
        for (int i = 0; i < 6; i++) budget = reserve(budget, TurnExecutionBudget.ReservationKind.TOOL, id(30 + i)).budget();
        var tools = budget;
        assertThatThrownBy(() -> reserve(tools, TurnExecutionBudget.ReservationKind.TOOL, id(40)))
                .isInstanceOf(ExecutionBudgetRejectedException.class);
    }

    @Test void repairQuotaIsSeparateAndZeroCanDisableIt() {
        var budget = start(POLICY);
        budget = reserve(budget, TurnExecutionBudget.ReservationKind.REPAIR, id(51)).budget();
        var finalBudget = budget;
        assertThatThrownBy(() -> reserve(finalBudget, TurnExecutionBudget.ReservationKind.REPAIR, id(52)))
                .isInstanceOf(ExecutionBudgetRejectedException.class);
        assertThat(budget.snapshot().reservations()).hasSize(1);
        var disabled = new TurnExecutionPolicy("v2", Duration.ofSeconds(2), 1, 1, 0);
        assertThatThrownBy(() -> reserve(start(disabled), TurnExecutionBudget.ReservationKind.REPAIR, id(53)))
                .isInstanceOf(ExecutionBudgetRejectedException.class);
    }

    @Test void duplicateSameKindIsIdempotentButCrossKindConflicts() {
        var once = reserve(start(POLICY), TurnExecutionBudget.ReservationKind.PROVIDER, id(60));
        var replay = once.budget().reserve(TurnExecutionBudget.ReservationKind.PROVIDER, id(60), FENCE, NOW);
        assertThat(replay.newlyReserved()).isFalse();
        assertThat(replay.budget().snapshot().reservations()).hasSize(1);
        assertThatThrownBy(() -> replay.budget().reserve(TurnExecutionBudget.ReservationKind.TOOL, id(60), FENCE, NOW))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.OPERATION_ID_CONFLICT));
    }

    @Test void deadlineAndFenceAreCheckedIncludingOnReplay() {
        var budget = reserve(start(POLICY), TurnExecutionBudget.ReservationKind.PROVIDER, id(70)).budget();
        assertThatThrownBy(() -> budget.reserve(TurnExecutionBudget.ReservationKind.PROVIDER, id(70), FENCE,
                NOW.plus(Duration.ofSeconds(45))))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.DEADLINE_EXCEEDED));
        assertThatThrownBy(() -> budget.reserve(TurnExecutionBudget.ReservationKind.TOOL, id(71), new TurnExecutionBudget.Fence(2, "owner"), NOW))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.STALE_FENCE));
        assertThatThrownBy(() -> budget.reserve(TurnExecutionBudget.ReservationKind.TOOL, id(71), new TurnExecutionBudget.Fence(1, "other"), NOW))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.STALE_FENCE));
    }

    @Test void restorePreservesFullIdentityAndExhaustedProviderBudgetWithoutReset() {
        var budget = start(POLICY);
        for (int i = 0; i < POLICY.providerAttemptLimit(); i++)
            budget = reserve(budget, TurnExecutionBudget.ReservationKind.PROVIDER, id(80 + i)).budget();
        var snapshot = budget.snapshot();
        var supplied = new ArrayList<>(snapshot.reservations());
        var copied = new TurnExecutionBudget.Snapshot(snapshot.reference(), snapshot.policy(), snapshot.fence(), snapshot.startedAt(), snapshot.deadline(), supplied);
        supplied.clear();
        var restored = TurnExecutionBudget.restore(copied, POLICY);
        assertThat(restored.snapshot().reference()).isEqualTo(REF);
        assertThat(restored.snapshot().policy()).isEqualTo(POLICY);
        assertThat(restored.snapshot().fence()).isEqualTo(FENCE);
        assertThat(restored.snapshot().startedAt()).isEqualTo(NOW);
        assertThat(restored.snapshot().deadline()).isEqualTo(NOW.plusSeconds(45));
        assertThat(restored.snapshot().reservations()).containsExactly(
                new TurnExecutionBudget.Reservation(id(80), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(81), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(82), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(83), TurnExecutionBudget.ReservationKind.PROVIDER));
        assertThatThrownBy(() -> restored.snapshot().reservations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> restored.reserve(TurnExecutionBudget.ReservationKind.PROVIDER, id(84), FENCE, NOW))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.LIMIT_EXCEEDED));
        var replay = restored.reserve(TurnExecutionBudget.ReservationKind.PROVIDER, id(80), FENCE, NOW);
        assertThat(replay.newlyReserved()).isFalse();
        assertThat(replay.budget().snapshot().reservations()).hasSize(4);
        assertThatThrownBy(() -> TurnExecutionBudget.restore(copied,
                new TurnExecutionPolicy(POLICY.version(), Duration.ofSeconds(44), 4, 6, 1)))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.POLICY_MISMATCH));
        assertThatThrownBy(() -> TurnExecutionBudget.restore(copied,
                new TurnExecutionPolicy("different-version", POLICY.deadline(), 4, 6, 1)))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.POLICY_MISMATCH));
    }

    @Test void rejectsOverflowAndInvalidPolicy() {
        assertThatThrownBy(() -> new TurnExecutionPolicy("v", Duration.ZERO, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnExecutionPolicy("v", Duration.ofSeconds(-1), 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnExecutionPolicy("v", Duration.ofSeconds(1), 0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnExecutionPolicy("v", Duration.ofSeconds(1), 1, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnExecutionPolicy("v", Duration.ofSeconds(1), 1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TurnExecutionBudget.start(REF, POLICY, FENCE, Instant.MAX)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsNullRequiredValues() {
        assertThatThrownBy(() -> new TurnExecutionPolicy(null, Duration.ofSeconds(1), 1, 1, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TurnExecutionPolicy("v", null, 1, 1, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TurnExecutionBudget.start(null, POLICY, FENCE, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TurnExecutionBudget.start(REF, POLICY, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TurnExecutionBudget.start(REF, POLICY, FENCE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start(POLICY).reserve(null, id(1), FENCE, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start(POLICY).reserve(TurnExecutionBudget.ReservationKind.TOOL, null, FENCE, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test void rejectsMalformedAndOversizedSnapshots() {
        var reservation = new TurnExecutionBudget.Reservation(id(90), TurnExecutionBudget.ReservationKind.PROVIDER);
        var duplicate = new TurnExecutionBudget.Snapshot(REF, POLICY, FENCE, NOW, NOW.plusSeconds(45),
                java.util.List.of(reservation, reservation));
        assertThatThrownBy(() -> TurnExecutionBudget.restore(duplicate, POLICY))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT));
        var oversized = new TurnExecutionBudget.Snapshot(REF, POLICY, FENCE, NOW, NOW.plusSeconds(45), java.util.List.of(
                new TurnExecutionBudget.Reservation(id(91), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(92), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(93), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(94), TurnExecutionBudget.ReservationKind.PROVIDER),
                new TurnExecutionBudget.Reservation(id(95), TurnExecutionBudget.ReservationKind.PROVIDER)));
        assertThatThrownBy(() -> TurnExecutionBudget.restore(oversized, POLICY))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT));
        var traversed = new boolean[1];
        var hostile = new AbstractList<TurnExecutionBudget.Reservation>() {
            @Override public TurnExecutionBudget.Reservation get(int index) { traversed[0] = true; throw new AssertionError("must not traverse oversized input"); }
            @Override public int size() { return POLICY.providerAttemptLimit() + POLICY.toolCallLimit() + POLICY.repairLimit() + 1; }
        };
        assertThatThrownBy(() -> new TurnExecutionBudget.Snapshot(REF, POLICY, FENCE, NOW, NOW.plusSeconds(45), hostile))
                .isInstanceOfSatisfying(ExecutionBudgetRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT));
        assertThat(traversed[0]).isFalse();
    }

    private static TurnExecutionBudget start(TurnExecutionPolicy policy) { return TurnExecutionBudget.start(REF, policy, FENCE, NOW); }
    private static TurnExecutionBudget.ReservationResult reserve(TurnExecutionBudget budget, TurnExecutionBudget.ReservationKind kind, UUID operation) {
        return budget.reserve(kind, operation, FENCE, NOW);
    }
    private static UUID id(long value) { return new UUID(0, value); }
}
