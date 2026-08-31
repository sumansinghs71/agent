package com.chatbot.agent.tools.contract;

/**
 * How a tool is reached.
 *
 * <p>Protocol is kept orthogonal to {@link com.chatbot.agent.model.ToolModel.SideEffect}: how a tool
 * is invoked says nothing about how much damage it can do. Authorisation is decided on the side
 * effect; routing is decided here.
 */
public enum ToolProtocol {
    /** In-process Java implementation. */
    LOCAL,
    /** HTTP call, subject to the outbound allowlist and SSRF guard. */
    REST,
    /** Parameterised SQL against a registered datasource. */
    SQL,
    /** Model Context Protocol server, reached over the MCP client. */
    MCP,
    /** Code executed inside a container sandbox. */
    SANDBOX
}
