package com.chatbot.agent.tools.contract;

/** Whether a human must authorise an invocation, and under what constraint. */
public enum ApprovalPolicy {

    /** No approval needed. Only valid for tools that cannot cause irreversible effects. */
    NONE,

    /** A human with the required role must approve before the effect. */
    REQUIRED,

    /**
     * Approval required, and the approver must not be the requester.
     *
     * <p>Separation of duty. Without it, "requires approval" degrades to "requires the requester to
     * click a second button", which stops accidents but not intent.
     */
    FOUR_EYE,

    /** Reserved for a policy expression evaluated at request time. Not implemented. */
    CUSTOM
}
