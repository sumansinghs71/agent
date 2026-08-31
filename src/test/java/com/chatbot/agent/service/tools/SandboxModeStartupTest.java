package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.service.tools.sandbox.DockerSandbox;
import com.chatbot.agent.service.tools.sandbox.LocalProcessSandbox;
import com.chatbot.agent.service.tools.sandbox.PythonSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Startup behaviour of the sandbox selector.
 *
 * <p>Audit finding F-1: the shipped default was {@code LOCAL}, which runs generated tool code on the
 * host as the JVM user. Combined with unauthenticated tool creation that was a remote code execution
 * path. The default is now {@code DOCKER}, and selecting {@code LOCAL} without an explicit opt-in
 * must prevent the application from starting - a refused boot being far better than a running
 * process that silently has no isolation.
 */
class SandboxModeStartupTest {

    private static final String FLAG = "AGENT_ALLOW_UNSAFE_LOCAL_EXECUTION";

    private PythonJavaScriptToolExecutor build(ToolExecutionProperties props) {
        List<PythonSandbox> sandboxes = List.of(
                new LocalProcessSandbox(props), new DockerSandbox(props));
        return new PythonJavaScriptToolExecutor(
                new PythonScriptBuilder(props, new ObjectMapper()),
                new JavaScriptCodeWrapper(props),
                props,
                sandboxes,
                new AgentMetrics(new SimpleMeterRegistry()));
    }

    private ToolExecutionProperties props(String sandbox) {
        ToolExecutionProperties p = new ToolExecutionProperties();
        p.getPython().setSandbox(sandbox);
        return p;
    }

    @Test
    @DisplayName("the shipped default is DOCKER, not LOCAL")
    void defaultIsDocker() {
        assertEquals("DOCKER", new ToolExecutionProperties().getPython().getSandbox());
    }

    @Test
    @DisplayName("DOCKER starts cleanly")
    void dockerModeStarts() {
        PythonJavaScriptToolExecutor executor = build(props("DOCKER"));
        assertNotNull(executor);
        executor.shutdown();
    }

    @Test
    @DisplayName("LOCAL without the opt-in refuses to start")
    void localWithoutOptInFailsFast() {
        assertNull(System.getenv(FLAG),
                "This test asserts the refusal path; it is meaningless if the opt-in is set "
                + "in the environment running the build.");
        System.clearProperty(FLAG);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> build(props("LOCAL")));

        assertTrue(e.getMessage().contains("no isolation"), e.getMessage());
        assertTrue(e.getMessage().contains(FLAG), e.getMessage());
    }

    @Test
    @DisplayName("LOCAL starts only with the explicit opt-in, and that opt-in is not subtle")
    void localWithOptInStarts() {
        System.setProperty(FLAG, "true");
        try {
            PythonJavaScriptToolExecutor executor = build(props("LOCAL"));
            assertNotNull(executor);
            executor.shutdown();
        } finally {
            System.clearProperty(FLAG);
        }
    }

    @Test
    @DisplayName("a value other than true does not enable LOCAL")
    void nearMissValuesDoNotEnableLocal() {
        for (String value : new String[]{"", "1", "yes", "TRUE ", "false", "maybe"}) {
            System.setProperty(FLAG, value);
            try {
                if ("TRUE ".equals(value)) {
                    // trimmed and case-insensitive, so this one is a legitimate opt-in
                    assertDoesNotThrow(() -> build(props("LOCAL")).shutdown());
                } else {
                    assertThrows(IllegalStateException.class, () -> build(props("LOCAL")),
                            "value '" + value + "' must not enable unsafe local execution");
                }
            } finally {
                System.clearProperty(FLAG);
            }
        }
    }

    @Test
    @DisplayName("an unknown sandbox id is refused rather than defaulted")
    void unknownSandboxRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> build(props("SOMETHING_ELSE")));
        assertTrue(e.getMessage().contains("Unknown"), e.getMessage());
    }
}
