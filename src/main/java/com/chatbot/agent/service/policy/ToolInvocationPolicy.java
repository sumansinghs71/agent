package com.chatbot.agent.service.policy;

import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.security.InvocationPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ToolInvocationPolicy - the runtime's authority gate.
 *
 * <p><b>The model proposes; the runtime decides.</b> Before this class existed, a tool name and an
 * argument map produced by a language model were lifted out of JSON and dispatched directly
 * (see {@code ReasoningAgentService#parseIntentResponse}). The only limits enforced were resource
 * limits - depth, call count, wall clock. Nothing checked <em>authority</em>. A prompt injection
 * planted in an uploaded document or a tool description could therefore choose which tool ran and
 * with what arguments.
 *
 * <p>Every invocation - top-level or nested via {@code eztool()} - passes through
 * {@link #evaluate}. Denial is the default: any condition that cannot be positively established
 * results in a deny.
 *
 * <p>Checks, in order (cheapest and most decisive first):
 * <ol>
 *   <li>tool exists — an unknown tool is an explicit policy denial, not a lookup miss</li>
 *   <li>tool is enabled</li>
 *   <li>tool belongs to the tenant (chatbot) named in the request</li>
 *   <li>caller is authenticated</li>
 *   <li>caller's role permits the tool's side-effect class</li>
 *   <li>irreversible effects have an approval, which does not yet exist, so they are refused</li>
 *   <li>arguments match the tool's declared parameters</li>
 * </ol>
 *
 * <p>Scope note: this is M0 containment, not the full M3 typed-tool framework. Argument checking is
 * name/required/undeclared validation against the tool's declared parameters, not JSON Schema.
 * Side-effect classes are derived when not declared. Both are deliberate interim positions and are
 * documented as such in {@code docs/ADR/0002-runtime-authority-boundary.md}.
 */
@Service
@Slf4j
public class ToolInvocationPolicy {

    /** HTTP methods that only read. Anything else is assumed to write. */
    private static final Set<String> SAFE_HTTP_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Evaluate a proposed invocation.
     *
     * @param tool              the resolved tool, or {@code null} if it could not be resolved
     * @param requestedToolId   the identifier the caller asked for (used when {@code tool} is null)
     * @param requestedChatbotId the tenant the request was made against
     * @param principal         the authority the call is made on behalf of
     * @param params            the proposed arguments
     */
    public PolicyDecision evaluate(ToolModel.Tool tool,
                                   String requestedToolId,
                                   Long requestedChatbotId,
                                   InvocationPrincipal principal,
                                   Map<String, Object> params) {

        // 1. Unknown tool -> DENY. Stated as a policy decision so it is counted and audited
        //    alongside every other denial, rather than surfacing as an incidental lookup failure.
        if (tool == null) {
            return PolicyDecision.deny(PolicyDecision.UNKNOWN_TOOL,
                    "No such tool: " + requestedToolId, requestedToolId, null);
        }

        String toolId = tool.getFuncNameKey();
        SideEffect sideEffect = classify(tool);

        // 2. Explicitly disabled. Null means "not set" and is treated as enabled, so rows written
        //    before this field existed keep working.
        if (Boolean.FALSE.equals(tool.getEnabled())) {
            return PolicyDecision.deny(PolicyDecision.TOOL_DISABLED,
                    "Tool is disabled: " + toolId, toolId, sideEffect);
        }

        // 3. Tenant isolation. Without this, any caller could invoke any chatbot's tools by naming
        //    a different chatbotId in the path.
        if (tool.getChatbotId() != null
                && requestedChatbotId != null
                && !tool.getChatbotId().equals(requestedChatbotId)) {
            return PolicyDecision.deny(PolicyDecision.TENANT_MISMATCH,
                    "Tool does not belong to chatbot " + requestedChatbotId, toolId, sideEffect);
        }

        // 4. Authentication.
        if (principal == null || !principal.isAuthenticated()) {
            return PolicyDecision.deny(PolicyDecision.NOT_AUTHENTICATED,
                    "Unauthenticated callers may not invoke tools", toolId, sideEffect);
        }

        // 5. Authority. The role's side-effect budget must cover this tool's class.
        if (!principal.mayInvoke(sideEffect)) {
            return PolicyDecision.deny(PolicyDecision.INSUFFICIENT_AUTHORITY,
                    "Principal " + principal.getName() + " with roles " + principal.getRoles()
                            + " may not invoke a " + sideEffect + " tool",
                    toolId, sideEffect);
        }

        // 6. Irreversible effects require approval. The human-approval service is M2 work; until it
        //    exists the correct behaviour is to refuse, not to proceed unapproved.
        if (sideEffect == SideEffect.IRREVERSIBLE_WRITE) {
            return PolicyDecision.deny(PolicyDecision.APPROVAL_REQUIRED,
                    "Tool is classified IRREVERSIBLE_WRITE and requires an approval, "
                            + "which this runtime cannot yet obtain",
                    toolId, sideEffect);
        }

        // 7. Arguments must match what the tool declares.
        String schemaViolation = validateArguments(tool, params);
        if (schemaViolation != null) {
            return PolicyDecision.deny(PolicyDecision.SCHEMA_VIOLATION, schemaViolation, toolId, sideEffect);
        }

        return PolicyDecision.allow(toolId, sideEffect);
    }

    /**
     * Determine a tool's side-effect class.
     *
     * <p>An explicit declaration always wins. Otherwise the class is DERIVED, and derivation errs
     * towards danger: an unrecognised shape is never assumed to be read-only.
     *
     * <ul>
     *   <li>PYTHON / JAVASCRIPT -> {@code PRIVILEGED}. These execute code and can call other tools
     *       via {@code eztool()}, so their blast radius is not statically bounded.</li>
     *   <li>SQL -> {@code READ_ONLY} only when the statement clearly begins with SELECT or WITH;
     *       anything else is {@code IRREVERSIBLE_WRITE}.</li>
     *   <li>REST -> {@code READ_ONLY} for GET/HEAD/OPTIONS, otherwise {@code IRREVERSIBLE_WRITE}.
     *       A PUT or DELETE against a third-party API cannot be assumed undoable.</li>
     * </ul>
     */
    public SideEffect classify(ToolModel.Tool tool) {
        if (tool.getSideEffect() != null) {
            return tool.getSideEffect();
        }
        if (tool.getFunctionType() == null) {
            return SideEffect.PRIVILEGED;
        }

        return switch (tool.getFunctionType()) {
            case PYTHON, JAVASCRIPT -> SideEffect.PRIVILEGED;
            case SQL -> isReadOnlySql(tool.getSqlQuery())
                    ? SideEffect.READ_ONLY
                    : SideEffect.IRREVERSIBLE_WRITE;
            case REST -> {
                String method = tool.getHttpMethod();
                yield method != null && SAFE_HTTP_METHODS.contains(method.trim().toUpperCase(Locale.ROOT))
                        ? SideEffect.READ_ONLY
                        : SideEffect.IRREVERSIBLE_WRITE;
            }
        };
    }

    private boolean isReadOnlySql(String sql) {
        if (sql == null) {
            return false;
        }
        String normalised = sql.strip().toUpperCase(Locale.ROOT);
        return normalised.startsWith("SELECT") || normalised.startsWith("WITH");
    }

    /**
     * Validate proposed arguments against the tool's declared parameters.
     *
     * <p>Rejecting <em>undeclared</em> arguments matters as much as requiring declared ones: an
     * injected instruction that adds an extra key is trying to reach a code path the tool author
     * never described.
     *
     * @return null when valid, otherwise a description of the violation
     */
    private String validateArguments(ToolModel.Tool tool, Map<String, Object> params) {
        Map<String, Object> supplied = params == null ? Map.of() : params;

        if (tool.getParams() == null || tool.getParams().isEmpty()) {
            return supplied.isEmpty()
                    ? null
                    : "Tool declares no parameters but " + supplied.size() + " were supplied: "
                      + new LinkedHashSet<>(supplied.keySet());
        }

        Set<String> declared = new LinkedHashSet<>();
        for (ToolModel.ToolParameter p : tool.getParams()) {
            if (p.getParamNameKey() != null) {
                declared.add(p.getParamNameKey());
            }
        }

        Set<String> undeclared = new LinkedHashSet<>(supplied.keySet());
        undeclared.removeAll(declared);
        if (!undeclared.isEmpty()) {
            return "Undeclared parameters supplied: " + undeclared + ". Declared: " + declared;
        }

        for (ToolModel.ToolParameter p : tool.getParams()) {
            if (p.isRequired()
                    && !supplied.containsKey(p.getParamNameKey())
                    && p.getDefaultValue() == null) {
                return "Required parameter missing: " + p.getParamNameKey();
            }
        }

        return null;
    }
}
