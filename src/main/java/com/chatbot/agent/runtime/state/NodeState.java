package com.chatbot.agent.runtime.state;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a single execution node.
 *
 * <p>The legal transitions are declared here and nowhere else. A state machine whose rules live in
 * scattered {@code if} statements is not a state machine; it is a set of conventions that drift.
 *
 * @see <a href="../../../../../../../../docs/RUNTIME_DESIGN.md">RUNTIME_DESIGN.md</a>
 */
public enum NodeState {

    /** Created; dependencies not yet satisfied. */
    PENDING,

    /** All dependencies succeeded; eligible to be claimed by a scheduler. */
    READY,

    /** Claimed and executing. Entering this state requires winning an optimistic-locking update. */
    RUNNING,

    /** Parked before a side effect that requires a human decision. */
    WAITING_APPROVAL,

    /** Completed; result persisted. Terminal. */
    SUCCEEDED,

    /** Failed, attempts remain. Carries {@code nextAttemptAt}; the scheduler re-queues it when due. */
    FAILED_RETRYABLE,

    /** Failed with no attempts remaining, or a failure retrying cannot fix. Terminal. */
    FAILED_TERMINAL,

    /** Stopped by explicit cancellation. Terminal. */
    CANCELLED,

    /** A dependency failed terminally, so this can never run. Terminal. */
    SKIPPED;

    private static final Set<NodeState> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED_TERMINAL, CANCELLED, SKIPPED);

    /**
     * @return true if no transition may leave this state. Retrying a terminal node means starting a
     * new run, not resurrecting the node.
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** @return the states reachable from this one in a single transition */
    public Set<NodeState> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(READY, SKIPPED, CANCELLED);
            case READY -> EnumSet.of(RUNNING, SKIPPED, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED_RETRYABLE, FAILED_TERMINAL,
                    WAITING_APPROVAL, CANCELLED);
            case WAITING_APPROVAL -> EnumSet.of(READY, FAILED_TERMINAL, CANCELLED);
            case FAILED_RETRYABLE -> EnumSet.of(READY, FAILED_TERMINAL, CANCELLED);
            case SUCCEEDED, FAILED_TERMINAL, CANCELLED, SKIPPED -> EnumSet.noneOf(NodeState.class);
        };
    }

    public boolean canTransitionTo(NodeState target) {
        return target != null && allowedTransitions().contains(target);
    }

    /**
     * @throws IllegalStateTransitionException if the transition is not permitted. Throwing rather
     * than clamping is deliberate: a silently-corrected illegal transition hides the bug that
     * produced it, and the corrected state is a guess.
     */
    public NodeState transitionTo(NodeState target, String nodeId) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target, nodeId);
        }
        return target;
    }
}
