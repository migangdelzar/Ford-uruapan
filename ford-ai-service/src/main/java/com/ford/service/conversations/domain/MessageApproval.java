package com.ford.service.conversations.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable approval revision. Persistence must enforce CAS and atomically commit effects; this type never sends. */
public record MessageApproval(UUID id, UUID tenantId, UUID conversationId, UUID sourceReceiptId,
                              long version, long conversationVersion, AdvisorResponseDisposition disposition,
                              Status status, String draftText, boolean draftMayBeApproved,
                              Instant createdAt, Instant updatedAt, UUID reviewedBy, UUID outboundMessageId) {
    public MessageApproval {
        Objects.requireNonNull(id, "approval ID is required");
        Objects.requireNonNull(tenantId, "approval tenant is required");
        Objects.requireNonNull(conversationId, "approval conversation is required");
        Objects.requireNonNull(sourceReceiptId, "source receipt is required");
        Objects.requireNonNull(disposition, "approval disposition is required");
        Objects.requireNonNull(status, "approval status is required");
        Objects.requireNonNull(createdAt, "creation time is required");
        Objects.requireNonNull(updatedAt, "update time is required");
        require(version >= 0 && conversationVersion >= 0, "approval versions cannot be negative");
        require(!updatedAt.isBefore(createdAt), "approval update precedes creation");
        require(disposition.route() != AdvisorResponseDisposition.Route.AUTOMATIC, "automatic replies do not need approval");
        require(draftText == null || draftText.length() <= 2000, "approval draft is too long");
        if (draftText != null && draftText.isBlank()) draftText = null;
        require(disposition.route() != AdvisorResponseDisposition.Route.MANDATORY_HANDOFF || draftText == null,
                "mandatory handoff cannot retain a candidate response");
        require(!draftMayBeApproved || (status == Status.PENDING && draftText != null
                && disposition.route() == AdvisorResponseDisposition.Route.REVIEW), "draft is not approvable");
        require(status != Status.PENDING || reviewedBy == null, "pending draft cannot already be reviewed");
        require(status != Status.QUEUED || (reviewedBy != null && outboundMessageId != null && draftText != null),
                "queued approval requires reviewer, exact text, and outbound receipt");
        require(status == Status.QUEUED || outboundMessageId == null, "non-sending disposition cannot carry an outbound receipt");
        require((status != Status.REJECTED && status != Status.TAKEN_OVER) || reviewedBy != null,
                "review disposition requires an advisor");
    }

    /** Sendability must come from server evidence/authority validation, never a request-body flag. */
    public static MessageApproval pending(UUID id, UUID tenantId, UUID conversationId, UUID sourceReceiptId,
                                          long conversationVersion, AdvisorResponseDisposition disposition,
                                          String draftText, boolean draftMayBeApproved, Instant now) {
        return new MessageApproval(id, tenantId, conversationId, sourceReceiptId, 0, conversationVersion,
                disposition, Status.PENDING, draftText, draftMayBeApproved, now, now, null, null);
    }

    /** Structural actions only; the application must also filter by actor permissions and fresh send validation. */
    public Set<Action> allowedActions() {
        if (status == Status.REJECTED) return Set.of(Action.TAKEOVER);
        if (status != Status.PENDING) return Set.of();
        if (disposition.route() == AdvisorResponseDisposition.Route.MANDATORY_HANDOFF) return Set.of(Action.TAKEOVER);
        return draftMayBeApproved ? Set.of(Action.APPROVE, Action.EDIT, Action.REJECT, Action.TAKEOVER)
                : Set.of(Action.EDIT, Action.REJECT, Action.TAKEOVER);
    }

    public boolean blocksAutomatedReplies() { return status == Status.PENDING || status == Status.REJECTED; }

    /** Prepare only; does not validate replacement evidence, consume the approval, or queue delivery. */
    public PreparedAction prepare(Action action, long expectedVersion, long expectedConversationVersion, String exactText) {
        if (version != expectedVersion || conversationVersion != expectedConversationVersion) {
            throw new IllegalStateException("approval or conversation version is stale");
        }
        if (action == null || !allowedActions().contains(action)) throw new IllegalStateException("approval action is not allowed");
        if (action.sending()) {
            require(exactText != null && !exactText.isBlank() && exactText.length() <= 2000, "exact confirmed text is required");
            require(action != Action.APPROVE || exactText.equals(draftText), "approved text differs from the current draft");
        } else {
            require(exactText == null, "non-sending actions cannot carry message text");
        }
        return new PreparedAction(this, action, exactText);
    }

    /** Call only after the authorized action has succeeded inside the same persistence transaction. */
    public MessageApproval complete(PreparedAction command, UUID actorId, long resultingConversationVersion,
                                     UUID messageId, Instant now) {
        require(command != null && command.owner == this, "action belongs to another approval revision");
        Objects.requireNonNull(actorId, "advisor identity is required");
        Objects.requireNonNull(now, "completion time is required");
        require(!now.isBefore(updatedAt), "completion time precedes the current revision");
        require(resultingConversationVersion >= conversationVersion, "conversation version moved backwards");
        if (command.action.sending()) {
            require(messageId != null && resultingConversationVersion > conversationVersion,
                    "sending requires an actual outbound receipt and advanced conversation version");
        } else {
            require(messageId == null, "non-sending action cannot carry an outbound receipt");
            require(command.action != Action.TAKEOVER || resultingConversationVersion > conversationVersion,
                    "takeover must advance conversation control version");
        }
        var next = switch (command.action) {
            case APPROVE, EDIT -> Status.QUEUED;
            case REJECT -> Status.REJECTED;
            case TAKEOVER -> Status.TAKEN_OVER;
        };
        return new MessageApproval(id, tenantId, conversationId, sourceReceiptId, Math.addExact(version, 1),
                resultingConversationVersion, disposition, next,
                command.action.sending() ? command.exactText : draftText, false, createdAt, now, actorId, messageId);
    }

    public MessageApproval supersede(long newConversationVersion, Instant now) {
        if (!blocksAutomatedReplies()) throw new IllegalStateException("approval is already terminal");
        require(newConversationVersion > conversationVersion, "supersession requires a newer conversation version");
        Objects.requireNonNull(now, "supersession time is required");
        require(!now.isBefore(updatedAt), "supersession time precedes the current revision");
        return new MessageApproval(id, tenantId, conversationId, sourceReceiptId, Math.addExact(version, 1),
                newConversationVersion, disposition, Status.SUPERSEDED, null, false,
                createdAt, now, reviewedBy, null);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    @Override public String toString() {
        return "MessageApproval[id=" + id + ", version=" + version + ", status=" + status + "]";
    }

    public enum Status { PENDING, QUEUED, REJECTED, TAKEN_OVER, SUPERSEDED }
    public enum Action {
        APPROVE, EDIT, REJECT, TAKEOVER;
        public boolean sending() { return this == APPROVE || this == EDIT; }
    }

    public static final class PreparedAction {
        private final MessageApproval owner;
        private final Action action;
        private final String exactText;
        private PreparedAction(MessageApproval owner, Action action, String exactText) {
            this.owner = owner;
            this.action = action;
            this.exactText = exactText;
        }
        public Action action() { return action; }
        public String exactText() { return exactText; }
        @Override public String toString() {
            return "PreparedApprovalAction[id=" + owner.id + ", version=" + owner.version + ", action=" + action + "]";
        }
    }
}
