package com.chatbot.agent.tools.contract;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.model.RetryPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The canonical description of a tool, whatever protocol serves it.
 *
 * <p>One type describes a SQL query, a REST call, sandboxed code and an MCP tool alike. That
 * uniformity is the point: the authority gate, the scheduler and the audit trail all reason about
 * tools without knowing how any particular one is reached, so adding a protocol does not mean
 * teaching every policy about it.
 *
 * <p>Immutable. A definition is registered, then referenced by id; it is never mutated in place,
 * because a tool whose contract can change under a running graph makes the graph's validation
 * meaningless.
 */
public record ToolDefinition(
        String toolId,
        String name,
        String version,
        String description,

        /** JSON Schema for arguments. Validated before a node is created, and again before dispatch. */
        String inputSchema,

        /** JSON Schema for the result. Validated after invocation; a violation is a tool defect. */
        String outputSchema,

        SideEffect sideEffectClass,

        /** Scopes a principal must hold. Empty means role-based authorisation alone applies. */
        Set<String> permissionScopes,

        /** Owning tenant. A tool is never visible across tenants. */
        Long tenantId,

        Duration timeout,
        RetryPolicy retryPolicy,
        IdempotencyMode idempotencyMode,
        ApprovalPolicy approvalPolicy,

        /** Role an approver must hold for this tool, when approval applies. */
        String requiredApproverRole,

        ToolProtocol protocol,

        /** Protocol-specific target: SQL text, URL template, MCP tool name, script reference. */
        String endpoint,

        boolean enabled) {

    public ToolDefinition {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId is required");
        }
        if (sideEffectClass == null) {
            throw new IllegalArgumentException("sideEffectClass is required for " + toolId
                    + ": authorisation is decided on it, so it cannot be inferred later");
        }
        if (protocol == null) {
            throw new IllegalArgumentException("protocol is required for " + toolId);
        }

        permissionScopes = permissionScopes == null ? Set.of() : Set.copyOf(permissionScopes);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
        idempotencyMode = idempotencyMode == null ? IdempotencyMode.NONE : idempotencyMode;
        approvalPolicy = approvalPolicy == null ? ApprovalPolicy.NONE : approvalPolicy;
        version = version == null || version.isBlank() ? "1" : version;

        // An irreversible effect that needs no approval is almost always a mistake in the
        // definition rather than a deliberate choice, so it is rejected rather than accepted
        // quietly. Deliberate cases must say so by declaring the tool REVERSIBLE_WRITE.
        if (sideEffectClass == SideEffect.IRREVERSIBLE_WRITE && approvalPolicy == ApprovalPolicy.NONE) {
            throw new IllegalArgumentException(
                    "Tool '" + toolId + "' is IRREVERSIBLE_WRITE with approvalPolicy NONE. "
                    + "An irreversible effect must require approval.");
        }

        // Likewise, an effect-causing tool with no idempotency story cannot be retried safely.
        if (sideEffectClass != SideEffect.READ_ONLY && idempotencyMode == IdempotencyMode.NONE
                && retryPolicy.maxAttempts() > 1) {
            throw new IllegalArgumentException(
                    "Tool '" + toolId + "' causes effects, declares no idempotency mode, and permits "
                    + retryPolicy.maxAttempts() + " attempts. Either give it an idempotency mode or "
                    + "set maxAttempts to 1.");
        }
    }

    /** Whether an invocation of this tool must be approved before its effect. */
    public boolean requiresApproval() {
        return approvalPolicy == ApprovalPolicy.REQUIRED
                || approvalPolicy == ApprovalPolicy.FOUR_EYE;
    }

    public boolean requiresIdempotencyKey() {
        return idempotencyMode != IdempotencyMode.NONE;
    }
}
