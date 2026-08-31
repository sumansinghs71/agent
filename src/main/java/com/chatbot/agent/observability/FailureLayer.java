package com.chatbot.agent.observability;

/**
 * Which layer a failure originated in.
 *
 * <p>The distinction this exists to make is between a **model** failure and a
 * **runtime/tool/environment** failure. Those look identical in an aggregate error rate and call for
 * completely different responses: one is a prompt, dataset or model-selection problem, the other is
 * an engineering defect. Without attribution, a rise in failures cannot be routed to anyone.
 *
 * <p>The set is closed and small, so it is safe as a metric label.
 */
public enum FailureLayer {

    /** The model produced something unusable - malformed output, or no output. */
    MODEL,

    /** The plan itself was wrong: bad tool choice, impossible ordering, cyclic proposal. */
    PLANNER,

    /** The authority gate refused: unknown tool, insufficient authority, schema violation. */
    POLICY,

    /** Graph construction or validation failed. */
    GRAPH,

    /** Scheduling, claiming, lease or concurrency failure. */
    SCHEDULER,

    /** The tool ran and failed on its own terms. */
    TOOL,

    /** MCP transport, handshake or protocol failure. */
    MCP,

    /** Code execution was terminated by a resource bound or died in the container. */
    SANDBOX,

    RETRIEVAL,

    /** A third-party system the tool called. Not our defect, but our problem. */
    DOWNSTREAM,

    /** Rejected, expired, or awaiting a decision that never came. */
    APPROVAL,

    /** Database unavailability, optimistic-lock exhaustion, checkpoint failure. */
    PERSISTENCE,

    /** Output failed its schema or a guardrail. */
    VALIDATION,

    /**
     * Genuinely unattributed.
     *
     * <p>Kept as an explicit value rather than defaulting to a plausible layer. A misattributed
     * failure sends someone to investigate the wrong subsystem, which is worse than admitting the
     * attribution is missing - and a rising UNKNOWN rate is itself a signal worth alerting on.
     */
    UNKNOWN
}
