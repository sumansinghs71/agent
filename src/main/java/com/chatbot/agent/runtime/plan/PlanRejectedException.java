package com.chatbot.agent.runtime.plan;

import java.util.List;

/**
 * A proposed plan was refused before any part of it ran.
 *
 * <p>The whole plan is rejected, not the offending step alone. Executing the acceptable prefix of a
 * plan whose later steps were refused produces a partial effect nobody asked for, and leaves the
 * caller unable to tell what happened from the error.
 */
public class PlanRejectedException extends RuntimeException {

    private final List<String> reasons;

    public PlanRejectedException(List<String> reasons) {
        super("Plan rejected: " + String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
