package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.config.ToolExecutionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The docker argv IS the security contract for Python tool execution, so it is asserted directly
 * rather than inferred from a running container. These run without a Docker daemon.
 */
class DockerSandboxCommandTest {

    private List<String> command() {
        ToolExecutionProperties props = new ToolExecutionProperties();
        DockerSandbox sandbox = new DockerSandbox(props);
        return sandbox.buildCommand(Path.of("/var/eztool/scripts/abc.py"), "eztool-exec-1");
    }

    private void assertFlagValue(List<String> cmd, String flag, String expected) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0, "missing flag: " + flag);
        assertEquals(expected, cmd.get(i + 1), "wrong value for " + flag);
    }

    @Test
    @DisplayName("containment flags are present with safe defaults")
    void containmentDefaults() {
        List<String> cmd = command();

        assertFlagValue(cmd, "--network", "none");      // no network for tool code
        assertFlagValue(cmd, "--memory", "256m");
        assertFlagValue(cmd, "--cpus", "0.5");
        assertFlagValue(cmd, "--pids-limit", "64");
        assertFlagValue(cmd, "--user", "65534:65534");  // nobody
        assertFlagValue(cmd, "--cap-drop", "ALL");
        assertFlagValue(cmd, "--security-opt", "no-new-privileges");

        assertTrue(cmd.contains("--read-only"), "root filesystem must be read-only");
        assertTrue(cmd.contains("--rm"), "container must be disposable");
    }

    @Test
    @DisplayName("script is bind-mounted read-only, never piped through stdin")
    void scriptIsMountedReadOnlyAndStdinStaysOpen() {
        List<String> cmd = command();

        assertFlagValue(cmd, "-v", "/var/eztool/scripts/abc.py:/script.py:ro");

        // -i keeps stdin attached for the eztool() protocol. If the script were piped via stdin
        // instead of mounted, inter-tool calls would break.
        assertTrue(cmd.contains("-i"), "stdin must stay open for the eztool() protocol");
        assertFalse(cmd.contains("-"), "script must not be piped via stdin");
        assertEquals("/script.py", cmd.get(cmd.size() - 1), "interpreter must target the mounted script");
    }

    @Test
    @DisplayName("swap is disabled so --memory is a hard ceiling")
    void swapIsDisabled() {
        List<String> cmd = command();
        int mem = cmd.indexOf("--memory");
        int swap = cmd.indexOf("--memory-swap");
        assertTrue(swap >= 0, "--memory-swap must be set, else the container can swap past --memory");
        assertEquals(cmd.get(mem + 1), cmd.get(swap + 1));
    }

    @Test
    @DisplayName("container name is deterministic so a watchdog can docker kill it")
    void containerIsNamed() {
        assertFlagValue(command(), "--name", "eztool-exec-1");
    }

    @Test
    @DisplayName("interpreter runs in CPython isolated mode (-I, not the non-existent --isolated)")
    void isolatedModeFlag() {
        List<String> cmd = command();
        assertTrue(cmd.contains("-I"), "expected CPython isolated mode flag");
        assertFalse(cmd.contains("--isolated"), "--isolated is not a valid CPython flag");
    }
}
