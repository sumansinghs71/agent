package com.chatbot.agent.service.tools.sandbox;

import java.io.IOException;
import java.nio.file.Path;

/**
 * PythonSandbox - Strategy for launching the Python interpreter.
 *
 * <p>The whole point of this seam is that {@code PythonProtocolHandler} talks to the interpreter
 * over the process's stdin/stdout. It does not care what the process actually <em>is</em>. That
 * means the {@code eztool()} inter-tool protocol works unchanged whether Python runs as a local
 * subprocess (developer laptop) or inside a locked-down container (server).
 *
 * <p>Implementations must NOT pipe the script via stdin - stdin is reserved for the protocol.
 * The script is staged on disk and referenced by path.
 *
 * @see LocalProcessSandbox
 * @see DockerSandbox
 */
public interface PythonSandbox {

    /**
     * @return identifier matching {@code tool-execution.python.sandbox}
     */
    String id();

    /**
     * Launch the interpreter against a staged script.
     *
     * @param scriptPath  script staged on the host filesystem; must be readable by the sandbox
     * @param executionId owning ExecutionContext id, used to build a deterministic kill handle
     * @param timeoutMs   wall-clock budget; implementations may pass this to the runtime, but the
     *                    caller is still responsible for enforcing it via {@link SandboxHandle#forceKill()}
     * @return handle exposing the process and a reliable kill path
     */
    SandboxHandle launch(Path scriptPath, String executionId, long timeoutMs) throws IOException;
}
