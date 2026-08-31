package com.chatbot.agent.service.policy;

import com.chatbot.agent.model.ToolModel;

/**
 * The outcome of a single authority evaluation.
 *
 * <p>Always produced, allow or deny, so that every invocation carries an attributable decision
 * rather than only the failures being visible.
 *
 * @param allowed     whether dispatch may proceed
 * @param reason      stable, low-cardinality code - safe as a metric tag, never free text
 * @param detail      human-readable explanation for logs and API responses
 * @param toolId      the tool the decision concerns
 * @param sideEffect  the side-effect class the runtime attributed to the tool (may be derived)
 */
public record PolicyDecision(
        boolean allowed,
        String reason,
        String detail,
        String toolId,
        ToolModel.SideEffect sideEffect) {

    // Denial reasons. Stable identifiers - do not reword without updating dashboards.
    public static final String ALLOWED = "ALLOWED";
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
    public static final String TOOL_DISABLED = "TOOL_DISABLED";
    public static final String TENANT_MISMATCH = "TENANT_MISMATCH";
    public static final String NOT_AUTHENTICATED = "NOT_AUTHENTICATED";
    public static final String INSUFFICIENT_AUTHORITY = "INSUFFICIENT_AUTHORITY";
    public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String SCHEMA_VIOLATION = "SCHEMA_VIOLATION";

    public static PolicyDecision allow(String toolId, ToolModel.SideEffect sideEffect) {
        return new PolicyDecision(true, ALLOWED, "allowed", toolId, sideEffect);
    }

    public static PolicyDecision deny(String reason, String detail, String toolId, ToolModel.SideEffect sideEffect) {
        return new PolicyDecision(false, reason, detail, toolId, sideEffect);
    }
}
