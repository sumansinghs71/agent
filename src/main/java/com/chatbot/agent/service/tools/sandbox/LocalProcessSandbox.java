package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.config.ToolExecutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * LocalProcessSandbox - Runs Python directly on the host as a child of this JVM.
 *
 * <p>DEVELOPMENT ONLY. This provides no isolation: the interpreter runs as the JVM user with full
 * filesystem and network access. Use {@link DockerSandbox} on any shared or production host.
 */
@Component
@Slf4j
public class LocalProcessSandbox implements PythonSandbox {

    public static final String ID = "LOCAL";

    private final ToolExecutionProperties config;

    public LocalProcessSandbox(ToolExecutionProperties config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SandboxHandle launch(Path scriptPath, String executionId, long timeoutMs) throws IOException {
        ToolExecutionProperties.PythonConfig py = config.getPython();

        List<String> cmd = new ArrayList<>();
        cmd.add(py.getInterpreterPath());
        if (py.getInterpreterArgs() != null) {
            cmd.addAll(py.getInterpreterArgs());
        }
        cmd.add(scriptPath.toString());

        log.debug("[executionId={}] Launching local interpreter: {}", executionId, cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        // A child process inherits this JVM's entire environment by default - including every
        // provider API key and database password the application was started with. This mode has no
        // isolation to speak of, but there is no reason to hand tool code the credentials too.
        // (DockerSandbox does not need this: containers start with an empty environment.)
        pb.environment().clear();

        Process process = pb.start();

        return new SandboxHandle(process, process::destroyForcibly, "local:pid=" + process.pid());
    }
}
