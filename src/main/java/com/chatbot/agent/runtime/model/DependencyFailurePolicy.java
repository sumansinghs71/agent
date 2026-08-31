package com.chatbot.agent.runtime.model;

/** What happens to the rest of the graph when a node fails terminally. */
public enum DependencyFailurePolicy {

    /**
     * Default. A terminal node failure fails the run; dependents are skipped.
     *
     * <p>Continuing past an unexplained failure usually produces a second, more confusing failure
     * further downstream, with the original cause buried.
     */
    FAIL_FAST,

    /** Dependents are skipped, unrelated branches continue, and the run ends PARTIAL. */
    CONTINUE_ON_FAILURE
}
