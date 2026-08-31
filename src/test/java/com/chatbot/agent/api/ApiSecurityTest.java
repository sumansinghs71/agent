package com.chatbot.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The HTTP authority boundary.
 *
 * <p>Directly targets audit finding F-1: before M0, {@code /api/**} was {@code permitAll()}, so an
 * unauthenticated caller could POST a tool containing arbitrary Python and then execute it. Each
 * test here is one link in that chain.
 *
 * <p>These assert the OUTER boundary only. Which tool an authenticated caller may actually run is
 * decided per-invocation by {@code ToolInvocationPolicy} and covered by
 * {@code ToolInvocationPolicyTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A tool whose body is arbitrary Python - the payload from the F-1 attack chain. */
    private String maliciousPythonTool() throws Exception {
        return mapper.writeValueAsString(Map.of(
                "funcNameKey", "pwn",
                "label", "pwn",
                "functionType", "PYTHON",
                "pythonCode", "import importlib\no = importlib.import_module('os')\n"
        ));
    }

    private String executeRequest() throws Exception {
        return mapper.writeValueAsString(Map.of("funcNameKey", "pwn", "params", Map.of()));
    }

    // ------------------------------------------------------------------ anonymous

    @Test
    @WithAnonymousUser
    @DisplayName("anonymous cannot create a tool")
    void anonymousCannotCreateTool() throws Exception {
        mockMvc.perform(post("/api/tools/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPythonTool()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("anonymous cannot execute a tool")
    void anonymousCannotExecuteTool() throws Exception {
        mockMvc.perform(post("/api/tools/1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("anonymous cannot execute a tool via the chatbot route either")
    void anonymousCannotExecuteToolViaChatbotRoute() throws Exception {
        mockMvc.perform(post("/api/chatbots/1/execute-tool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executeRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("anonymous cannot chat, upload documents, or list tools")
    void anonymousCannotReachOtherApiRoutes() throws Exception {
        mockMvc.perform(post("/api/chatbots/1/chat")
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tools/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/documents/anything"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ ordinary user

    @Test
    @WithMockUser(username = "bob", roles = {"USER"})
    @DisplayName("a normal user cannot register an executable tool")
    void userCannotCreateTool() throws Exception {
        mockMvc.perform(post("/api/tools/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPythonTool()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", roles = {"USER"})
    void userCannotUpdateOrDeleteTools() throws Exception {
        mockMvc.perform(put("/api/tools/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPythonTool()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tools/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "op", roles = {"USER", "OPERATOR"})
    @DisplayName("an operator still cannot author tools - authoring is administrative")
    void operatorCannotCreateTool() throws Exception {
        mockMvc.perform(post("/api/tools/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPythonTool()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ admin

    @Test
    @WithMockUser(username = "admin", roles = {"USER", "OPERATOR", "ADMIN"})
    @DisplayName("admin passes the URL authority check for tool creation")
    void adminIsPermittedToReachToolCreation() throws Exception {
        // The request reaches the controller (no 401/403). It then fails on the database, which is
        // not present in CI - that is the point: authorization allowed it through, and the assertion
        // is about the boundary, not about persistence.
        int status = mockMvc.perform(post("/api/tools/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPythonTool()))
                .andReturn().getResponse().getStatus();

        org.junit.jupiter.api.Assertions.assertNotEquals(401, status, "admin must not be rejected as unauthenticated");
        org.junit.jupiter.api.Assertions.assertNotEquals(403, status, "admin must not be rejected as forbidden");
    }

    // ------------------------------------------------------------------ actuator

    @Test
    @WithAnonymousUser
    @DisplayName("only the health endpoint is anonymous; the rest of actuator is closed")
    void actuatorIsNotWideOpen() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
