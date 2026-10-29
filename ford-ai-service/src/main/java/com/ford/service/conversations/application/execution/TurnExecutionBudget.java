package com.ford.service.conversations.application.execution;

import com.ford.service.conversations.application.graph.ConversationTurnReference;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable in-memory budget value. It does not provide persistence, concurrency, or dispatch-once guarantees. */
public final class TurnExecutionBudget {
    public enum ReservationKind { PROVIDER, TOOL, REPAIR }
    public record Fence(long generation, String ownerToken) {
        public Fence {
            Objects.requireNonNull(ownerToken, "ownerToken");
            if (generation < 1 || ownerToken.isBlank()) throw new IllegalArgumentException("Invalid execution fence");
        }
    }
    public record Reservation(UUID operationId, ReservationKind kind) {
        public Reservation { Objects.requireNonNull(operationId, "operationId"); Objects.requireNonNull(kind, "kind"); }
    }
    public record Snapshot(ConversationTurnReference reference, TurnExecutionPolicy policy, Fence fence,
                           Instant startedAt, Instant deadline, List<Reservation> reservations) {
        public Snapshot {
            Objects.requireNonNull(reference, "reference"); Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(fence, "fence"); Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(reservations, "reservations");
            long maximumReservations = (long) policy.providerAttemptLimit() + policy.toolCallLimit() + policy.repairLimit();
            int reservationCount = reservations.size();
            if (reservationCount < 0 || reservationCount > maximumReservations)
                reject(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT);
            reservations = List.copyOf(reservations);
        }
    }
    public record ReservationResult(TurnExecutionBudget budget, boolean newlyReserved) {
        public ReservationResult { Objects.requireNonNull(budget, "budget"); }
    }

    private final Snapshot snapshot;
    private TurnExecutionBudget(Snapshot snapshot) { this.snapshot = snapshot; }

    public static TurnExecutionBudget start(ConversationTurnReference reference, TurnExecutionPolicy policy, Fence fence, Instant now) {
        Objects.requireNonNull(reference, "reference"); Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(fence, "fence"); Objects.requireNonNull(now, "now");
        final Instant deadline;
        try { deadline = now.plus(policy.deadline()); }
        catch (RuntimeException overflow) { throw new IllegalArgumentException("Budget deadline overflow", overflow); }
        return new TurnExecutionBudget(new Snapshot(reference, policy, fence, now, deadline, List.of()));
    }

    public static TurnExecutionBudget restore(Snapshot snapshot, TurnExecutionPolicy expectedPolicy) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(expectedPolicy, "expectedPolicy");
        if (!snapshot.policy().equals(expectedPolicy)) reject(ExecutionBudgetRejectedException.Reason.POLICY_MISMATCH);
        final Instant expectedDeadline;
        try { expectedDeadline = snapshot.startedAt().plus(snapshot.policy().deadline()); }
        catch (RuntimeException overflow) { reject(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT); return null; }
        if (!expectedDeadline.equals(snapshot.deadline())) reject(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT);
        var ids = new HashSet<UUID>();
        int providers = 0, tools = 0, repairs = 0;
        for (var reservation : snapshot.reservations()) {
            if (reservation == null || !ids.add(reservation.operationId())) reject(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT);
            switch (reservation.kind()) {
                case PROVIDER -> providers++;
                case TOOL -> tools++;
                case REPAIR -> repairs++;
            }
        }
        if (providers > expectedPolicy.providerAttemptLimit() || tools > expectedPolicy.toolCallLimit()
                || repairs > expectedPolicy.repairLimit()) reject(ExecutionBudgetRejectedException.Reason.INVALID_SNAPSHOT);
        return new TurnExecutionBudget(snapshot);
    }

    public ReservationResult reserve(ReservationKind kind, UUID operationId, Fence expectedFence, Instant now) {
        Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(expectedFence, "expectedFence"); Objects.requireNonNull(now, "now");
        if (!snapshot.fence().equals(expectedFence)) reject(ExecutionBudgetRejectedException.Reason.STALE_FENCE);
        if (!now.isBefore(snapshot.deadline())) reject(ExecutionBudgetRejectedException.Reason.DEADLINE_EXCEEDED);
        var prior = snapshot.reservations().stream().filter(r -> r.operationId().equals(operationId)).findFirst();
        if (prior.isPresent()) {
            if (prior.get().kind() != kind) reject(ExecutionBudgetRejectedException.Reason.OPERATION_ID_CONFLICT);
            return new ReservationResult(this, false);
        }
        long used = snapshot.reservations().stream().filter(r -> r.kind() == kind).count();
        int limit = switch (kind) {
            case PROVIDER -> snapshot.policy().providerAttemptLimit();
            case TOOL -> snapshot.policy().toolCallLimit();
            case REPAIR -> snapshot.policy().repairLimit();
        };
        if (used >= limit) reject(ExecutionBudgetRejectedException.Reason.LIMIT_EXCEEDED);
        var reservations = new java.util.ArrayList<>(snapshot.reservations());
        reservations.add(new Reservation(operationId, kind));
        return new ReservationResult(new TurnExecutionBudget(new Snapshot(snapshot.reference(), snapshot.policy(), snapshot.fence(),
                snapshot.startedAt(), snapshot.deadline(), reservations)), true);
    }

    public Snapshot snapshot() { return snapshot; }
    private static void reject(ExecutionBudgetRejectedException.Reason reason) { throw new ExecutionBudgetRejectedException(reason); }
}
