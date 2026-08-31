package com.chatbot.agent.runtime.exec;

import com.chatbot.agent.runtime.graph.ExecutionNode;

import java.util.UUID;

/**
 * Performs the work of one node.
 *
 * <p>This seam keeps the scheduler testable. Scheduling, retry, recovery and idempotency semantics
 * are what M2 is about; whether the node calls a SQL tool or a container is irrelevant to them. A
 * deterministic test executor exercises every scheduling path without a database, a network, or a
 * language model - which is what makes the crash and concurrency tests fast enough to run on
 * every commit.
 */
@FunctionalInterface
public interface NodeExecutor {

    /**
     * @param runId   the run this attempt belongs to
     * @param node    the node to execute
     * @param attempt 1-based attempt number
     * @return the outcome. Implementations should not throw; a thrown exception is treated as an
     *         ambiguous failure, since the runtime cannot tell how far the work got.
     */
    NodeResult execute(UUID runId, ExecutionNode node, int attempt);
}
