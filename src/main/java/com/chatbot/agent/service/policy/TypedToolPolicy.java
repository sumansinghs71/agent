package com.chatbot.agent.service.policy;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.tools.contract.ApprovalPolicy;
import com.chatbot.agent.tools.contract.SchemaValidator;
import com.chatbot.agent.tools.contract.ToolDefinition;
import com.chatbot.agent.tools.registry.ToolCatalog;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * The authority gate over the typed tool contract.
 *
 * <p>Supersedes the name-and-required argument checking of the earlier policy: arguments are now
 * validated against the tool's declared JSON Schema, and authorisation additionally considers
 * permission scopes and the tool's approval policy.
 *
 * <p>Denial is the default. Any condition that cannot be positively established denies, and every
 * decision - allow or deny - is returned so that it can be counted and audited. A gate that only
 * reports its refusals cannot be distinguished from one that has stopped being consulted.
 */
@Slf4j
public class TypedToolPolicy {

    private final ToolCatalog catalog;
    private final SchemaValidator schemaValidator;

    public TypedToolPolicy(ToolCatalog catalog, SchemaValidator schemaValidator) {
        this.catalog = catalog;
        this.schemaValidator = schemaValidator;
    }

    /**
     * Decide whether {@code principal} may invoke {@code toolId} in {@code tenantId} with
     * {@code arguments}.
     *
     * <p>Note what this does NOT decide: whether an approval has actually been granted. That is
     * runtime state, not policy, and is enforced by the scheduler before the node executes. This
     * method reports that approval is required; the scheduler ensures it was obtained.
     */
    public PolicyDecision evaluate(Long tenantId, String toolId,
                                   InvocationPrincipal principal, Map<String, Object> arguments) {

        // Tenant-scoped lookup: a tool belonging to another tenant is indistinguishable from one
        // that does not exist, which avoids turning denials into an inventory oracle.
        Optional<ToolDefinition> found = catalog.find(tenantId, toolId);
        if (found.isEmpty()) {
            return PolicyDecision.deny(PolicyDecision.UNKNOWN_TOOL,
                    "No such tool: " + toolId, toolId, null);
        }

        ToolDefinition tool = found.get();
        SideEffect effect = tool.sideEffectClass();

        if (!tool.enabled()) {
            return PolicyDecision.deny(PolicyDecision.TOOL_DISABLED,
                    "Tool is disabled: " + toolId, toolId, effect);
        }

        if (principal == null || !principal.isAuthenticated()) {
            return PolicyDecision.deny(PolicyDecision.NOT_AUTHENTICATED,
                    "Unauthenticated callers may not invoke tools", toolId, effect);
        }

        if (!principal.mayInvoke(effect)) {
            return PolicyDecision.deny(PolicyDecision.INSUFFICIENT_AUTHORITY,
                    "Principal " + principal.getName() + " with roles " + principal.getRoles()
                            + " may not invoke a " + effect + " tool", toolId, effect);
        }

        // Scopes are an additional, finer constraint on top of roles - a role says what class of
        // effect is permitted, a scope says which particular capability.
        if (!tool.permissionScopes().isEmpty()
                && !principal.getRoles().containsAll(tool.permissionScopes())) {
            return PolicyDecision.deny(PolicyDecision.INSUFFICIENT_AUTHORITY,
                    "Tool requires scopes " + tool.permissionScopes(), toolId, effect);
        }

        SchemaValidator.Result validation = schemaValidator.validate(tool.inputSchema(), arguments);
        if (!validation.valid()) {
            return PolicyDecision.deny(PolicyDecision.SCHEMA_VIOLATION,
                    "Arguments do not satisfy the tool's input schema: " + validation.summary(),
                    toolId, effect);
        }

        return PolicyDecision.allow(toolId, effect);
    }

    /**
     * Whether an invocation must be approved before its effect, and by whom.
     *
     * <p>Separate from {@link #evaluate} because "may this principal ask for this?" and "has a human
     * authorised it?" are different questions answered at different times: the first at planning,
     * the second immediately before the effect.
     */
    public boolean requiresApproval(Long tenantId, String toolId) {
        return catalog.find(tenantId, toolId).map(ToolDefinition::requiresApproval).orElse(true);
    }

    /** True when the approver must differ from the requester. */
    public boolean requiresSeparationOfDuty(Long tenantId, String toolId) {
        return catalog.find(tenantId, toolId)
                .map(t -> t.approvalPolicy() == ApprovalPolicy.FOUR_EYE)
                .orElse(false);
    }

    /**
     * Validate a tool's result against its declared output schema.
     *
     * <p>A violation is a defect in the tool, not in the caller, and is reported as such. Validating
     * output matters because a downstream node consumes it: an unchecked malformed result propagates
     * into the next node's arguments, where the eventual failure points at the wrong tool.
     */
    public SchemaValidator.Result validateOutput(Long tenantId, String toolId, Object result) {
        return catalog.find(tenantId, toolId)
                .map(t -> schemaValidator.validate(t.outputSchema(), result))
                .orElse(SchemaValidator.Result.ok());
    }
}
