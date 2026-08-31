package com.chatbot.agent.tools.mcp;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.tools.contract.ApprovalPolicy;
import com.chatbot.agent.tools.contract.IdempotencyMode;
import com.chatbot.agent.tools.contract.ToolDefinition;
import com.chatbot.agent.tools.contract.ToolProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;

/**
 * Maps a discovered MCP tool onto the canonical {@link ToolDefinition}.
 *
 * <p>Once mapped, an MCP tool is indistinguishable from a SQL or REST tool to the authority gate,
 * the planner and the scheduler. That uniformity is the point of the contract: adding a protocol
 * must not mean teaching every policy about it.
 *
 * <p><b>MCP does not describe side effects.</b> A server advertises a name, a description and an
 * input schema; nothing states whether calling the tool changes anything. Since authorisation is
 * decided on the side-effect class, the missing value is supplied conservatively rather than
 * guessed: a discovered tool is {@code PRIVILEGED} unless an operator has classified it. Inferring
 * "read-only" from a name like {@code get_*} would be a naming convention masquerading as a
 * security control.
 */
public class McpToolMapper {

    private final ObjectMapper mapper;
    private final Duration defaultTimeout;

    public McpToolMapper(ObjectMapper mapper, Duration defaultTimeout) {
        this.mapper = mapper;
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * @param serverName    used to namespace the tool id, so two servers offering "search" do not collide
     * @param classification operator-supplied side effect, or null to default to PRIVILEGED
     */
    public ToolDefinition toDefinition(String serverName, McpClient.McpTool tool,
                                       Long tenantId, SideEffect classification) {

        SideEffect effect = classification == null ? SideEffect.PRIVILEGED : classification;

        String schema = tool.inputSchema() == null ? null : tool.inputSchema().toString();

        ApprovalPolicy approval = effect == SideEffect.IRREVERSIBLE_WRITE
                ? ApprovalPolicy.REQUIRED
                : ApprovalPolicy.NONE;

        IdempotencyMode idempotency = effect == SideEffect.READ_ONLY
                ? IdempotencyMode.NONE
                : IdempotencyMode.DERIVED;

        return new ToolDefinition(
                "mcp:" + serverName + ":" + tool.name(),
                tool.name(),
                "1",
                tool.description(),
                schema,
                null,                     // MCP does not advertise an output schema
                effect,
                Set.of(),
                tenantId,
                defaultTimeout,
                RetryPolicy.DEFAULT,
                idempotency,
                approval,
                approval == ApprovalPolicy.NONE ? null : "ROLE_ADMIN",
                ToolProtocol.MCP,
                tool.name(),              // the server-side name to call
                true);
    }
}
