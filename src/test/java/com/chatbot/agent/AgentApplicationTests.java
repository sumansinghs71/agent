package com.chatbot.agent;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.service.policy.ToolInvocationPolicy;
import com.chatbot.agent.service.tools.ToolExecutionService;
import com.chatbot.agent.service.tools.sandbox.DockerSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Context smoke test.
 *
 * <p>Every line of this class was previously commented out, so nothing verified that the
 * application could start. That gap hid a real defect: {@code tool-execution.inter-tool-communication.
 * aggregate-timeout-seconds} carried a default that violated its own {@code @Min} constraint, which
 * made property binding fail and the application refuse to boot. Compilation had always succeeded.
 *
 * <p>Beyond "it starts", this asserts the security posture the context is wired with, so that a
 * later change cannot quietly restore an unsafe default.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolExecutionProperties properties;

    @Test
    @DisplayName("the application context loads")
    void contextLoads() {
        assertNotNull(context);
    }

    @Test
    @DisplayName("the authority gate is wired into tool execution")
    void authorityGateIsPresent() {
        assertNotNull(context.getBean(ToolInvocationPolicy.class));
        assertNotNull(context.getBean(ToolExecutionService.class));
    }

    @Test
    @DisplayName("the container sandbox is available and is the shipped default")
    void sandboxDefaultIsContainerised() {
        assertNotNull(context.getBean(DockerSandbox.class));
        assertEquals("DOCKER", properties.getPython().getSandbox(),
                "The shipped default must be DOCKER; LOCAL provides no isolation");
    }

    @Test
    @DisplayName("the outbound allowlist is enforced, not merely advisory")
    void outboundAllowlistIsEnforced() {
        assertTrue(properties.getSecurity().isEnforceOutboundAllowlist());
        assertFalse(properties.getSecurity().getAllowedOutboundHosts().isEmpty());
    }

    @Test
    @DisplayName("Authorization is not a header a tool may set")
    void authorizationHeaderIsNotAllowlisted() {
        assertFalse(properties.getSecurity().getAllowedRequestHeaders().contains("authorization"));
    }

    @Test
    @DisplayName("the dead CodeValidatorService is gone, so nothing can mistake it for a sandbox")
    void deadValidatorIsRemoved() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.chatbot.agent.service.tools.CodeValidatorService"));
    }
}
