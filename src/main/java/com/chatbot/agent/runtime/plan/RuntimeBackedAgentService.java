package com.chatbot.agent.runtime.plan;

import com.chatbot.agent.runtime.exec.AgentRunService;
import com.chatbot.agent.runtime.exec.RunScheduler;
import com.chatbot.agent.runtime.model.DependencyFailurePolicy;
import com.chatbot.agent.runtime.state.RunStatus;
import com.chatbot.agent.security.InvocationPrincipal;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The only path from a model-proposed plan to executed work.
 *
 * <p>Replaces the arrangement in which the reasoning service called the tool executor directly.
 * That path bypassed the runtime entirely: no durable record, no recovery, no approval gate, and
 * an authority check that ran per-call rather than over the plan as a whole.
 *
 * <pre>
 *   planner proposal
 *     → AgentPlanner        validate every step, or reject the whole plan
 *       → ExecutionGraph    acyclic by construction
 *         → AgentRunService persist before executing
 *           → RunScheduler  claim, execute, checkpoint, recover
 * </pre>
 *
 * <p>Direct tool invocation still exists for administrative and debugging use, reached through an
 * authenticated endpoint and its own authority check. What no longer exists is a path where
 * <em>agent-controlled</em> execution avoids the runtime.
 */
@Slf4j
public class RuntimeBackedAgentService {

    private final AgentPlanner planner;
    private final AgentRunService runs;
    private final RunScheduler scheduler;
    private final int maxTicks;

    public RuntimeBackedAgentService(AgentPlanner planner, AgentRunService runs,
                                     RunScheduler scheduler, int maxTicks) {
        this.planner = planner;
        this.runs = runs;
        this.scheduler = scheduler;
        this.maxTicks = maxTicks;
    }

    /** The outcome of submitting a plan. */
    public record Execution(UUID runId, RunStatus status) {
        /** A run parked awaiting a human decision has not failed; it is waiting. */
        public boolean awaitingApproval() {
            return status == RunStatus.WAITING_APPROVAL;
        }
    }

    /**
     * Validate, persist and execute a proposed plan.
     *
     * @throws PlanRejectedException if any step is unauthorised, unknown or schema-invalid - in
     *         which case no run is created and nothing executes
     */
    public Execution submit(Long tenantId, InvocationPrincipal principal,
                            List<PlannedStep> steps, Instant deadlineAt) {

        AgentPlanner.AcceptedPlan accepted = planner.accept(tenantId, principal, steps);

        runs.createRun(accepted.runId(), accepted.graph(), principal,
                DependencyFailurePolicy.FAIL_FAST, 20, deadlineAt);

        RunStatus status = scheduler.runToCompletion(accepted.runId(), maxTicks);
        log.info("Run {} finished with status {}", accepted.runId(), status);

        return new Execution(accepted.runId(), status);
    }

    /** Resume a run after an out-of-band event, such as an approval being granted. */
    public Execution resume(UUID runId) {
        return new Execution(runId, scheduler.runToCompletion(runId, maxTicks));
    }

    public RunStatus status(UUID runId) {
        return runs.status(runId);
    }
}
