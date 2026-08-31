package com.chatbot.agent.runtime.state;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a whole run. Derived from node states, but stored so it is queryable directly. */
public enum RunStatus {

    PENDING,
    RUNNING,

    /** Every node reached a terminal state and none failed. */
    SUCCEEDED,

    /** At least one node failed terminally under FAIL_FAST. */
    FAILED,

    /**
     * Under CONTINUE_ON_FAILURE: some branches succeeded, others failed. Distinct from FAILED
     * because "nothing worked" and "half of it worked" call for different operator responses.
     */
    PARTIAL,

    CANCELLED,

    /** At least one node is parked awaiting a human decision. */
    WAITING_APPROVAL;

    private static final Set<RunStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, PARTIAL, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
