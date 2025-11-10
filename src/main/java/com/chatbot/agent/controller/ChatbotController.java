package com.chatbot.agent.controller;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.model.CitationModel;
import com.chatbot.agent.model.ToolExecutionResult;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.service.ChatbotService;
import com.chatbot.agent.service.tools.ToolExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chatbots")
public class ChatbotController {

    private final ChatbotService chatbotService;
    private final ToolExecutionService toolExecutionService;
    private static final Logger log = LoggerFactory.getLogger(ChatbotController.class);

    public ChatbotController(ChatbotService chatbotService, ToolExecutionService toolExecutionService) {
        this.chatbotService = chatbotService;
        this.toolExecutionService = toolExecutionService;
    }

    /**
     * Chat endpoint - handles regular chat messages
     *
     * @param chatbotId The chatbot ID
     * @param message The user message
     * @param userDetails User details (from Spring Security, can be null if not configured)
     * @return Chat response
     */
    @PostMapping("/{chatbotId}/chat")
    public ResponseEntity<String> chat(
            @PathVariable Long chatbotId,
            @RequestBody String message,
            @AuthenticationPrincipal UserDetails userDetails) {

        String userId = getUserId(userDetails);
        String requestId = UUID.randomUUID().toString();

        // Set request ID in MDC (inherited by all threads in this request)
        MDC.put("requestId", requestId);
        MDC.put("chatbotId", String.valueOf(chatbotId));
        MDC.put("userId", userId);

        try {
            log.info("Chat request received: chatbot={}, user={}, messageLength={}",
                    chatbotId, userId, message.length());

            // Process chat message (your existing logic)
            String response = chatbotService.handleChat(chatbotId, message);

            log.info("Chat request completed successfully");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Chat request failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing chat: " + e.getMessage());

        } finally {
            MDC.clear();
        }
    }

    /**
     * Execute tool directly - NEW ENDPOINT
     *
     * Allows direct tool execution with parameters
     * Useful for testing and direct tool invocation
     *
     * @param chatbotId The chatbot ID
     * @param request Tool execution request
     * @param userDetails User details
     * @return Tool execution result
     */
    @PostMapping("/{chatbotId}/execute-tool")
    public ResponseEntity<ToolExecutionResult> executeTool(
            @PathVariable Long chatbotId,
            @RequestBody ToolModel.ToolExecutionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        String userId = getUserId(userDetails);
        String requestId = UUID.randomUUID().toString();

        MDC.put("requestId", requestId);
        MDC.put("chatbotId", String.valueOf(chatbotId));
        MDC.put("userId", userId);

        try {
            log.info("Tool execution request: chatbot={}, user={}, tool={}",
                    chatbotId, userId, request.getFuncNameKey());

            // Execute tool with context management
            ToolExecutionResult result = toolExecutionService.executeTool(chatbotId, userId, request);

            if (result.isSuccess()) {
                log.info("Tool execution completed successfully: tool={}, executionTime={}ms",
                        request.getFuncNameKey(), result.getExecutionTimeMs());
                return ResponseEntity.ok(result);
            } else {
                log.warn("Tool execution failed: tool={}, error={}",
                        request.getFuncNameKey(), result.getError());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
            }

        } catch (Exception e) {
            log.error("Tool execution request failed", e);

            ToolExecutionResult errorResult = ToolExecutionResult.failure(
                    "Error executing tool: " + e.getMessage(),
                    "INTERNAL_ERROR"
            );

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResult);

        } finally {
            MDC.clear();
        }
    }

    /**
     * Get tool execution status - NEW ENDPOINT
     *
     * Check if a tool execution is still running
     *
     * @param chatbotId The chatbot ID
     * @param executionId The execution ID
     * @return Execution status
     */
    @GetMapping("/{chatbotId}/executions/{executionId}/status")
    public ResponseEntity<Map<String, Object>> getExecutionStatus(
            @PathVariable Long chatbotId,
            @PathVariable String executionId) {

        try {
            // Check if execution is still active
            // You would need to add this method to ExecutionContextFactory
            // For now, return a simple response

            Map<String, Object> status = Map.of(
                    "executionId", executionId,
                    "status", "completed", // or "running", "timeout", "failed"
                    "message", "Execution tracking not yet implemented"
            );

            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("Error getting execution status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List available tools for chatbot - NEW ENDPOINT
     *
     * @param chatbotId The chatbot ID
     * @return List of tools
     */
    @GetMapping("/{chatbotId}/tools")
    public ResponseEntity<?> listTools(@PathVariable Long chatbotId) {

        try {
            log.info("Listing tools for chatbot: {}", chatbotId);

            var tools = toolExecutionService.getToolsForChatbot(chatbotId);

            return ResponseEntity.ok(tools);

        } catch (Exception e) {
            log.error("Error listing tools", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get user ID from authentication or use "anonymous"
     */
    private String getUserId(UserDetails userDetails) {
        if (userDetails != null) {
            return userDetails.getUsername();
        }
        return "anonymous";
    }

    // Request/Response classes
    @Data
    public static class ChatRequest {
        private String message;
    }

    @Data
    public static class InstructionUpdateRequest {
        private String systemInstruction;
        private String userInstruction;
        private Boolean enabled;
    }

    @Data
    public static class InstructionResponse {
        private String systemInstruction;
        private String userInstruction;
        private Boolean enabled;
    }
}