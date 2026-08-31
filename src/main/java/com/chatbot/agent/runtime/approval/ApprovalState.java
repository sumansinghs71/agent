package com.chatbot.agent.runtime.approval;

/** Lifecycle of one approval request. */
public enum ApprovalState {
    PENDING,
    APPROVED,
    REJECTED,

    /**
     * The decision window elapsed without one.
     *
     * <p>Distinct from REJECTED: nobody decided against the action, nobody decided at all. Treating
     * silence as approval would be unsafe, and treating it as rejection would misreport why the run
     * stopped.
     */
    EXPIRED
}
