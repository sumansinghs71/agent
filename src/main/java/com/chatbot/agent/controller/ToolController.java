package com.chatbot.agent.controller;

import com.chatbot.agent.model.ToolExecutionResult;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.service.tools.ToolExecutionService;
import com.chatbot.agent.repository.ToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    private final ToolRepository toolRepository;
    private final ToolExecutionService toolExecutionService;

    public ToolController(ToolRepository toolRepository,
                          ToolExecutionService toolExecutionService) {
        this.toolRepository = toolRepository;
        this.toolExecutionService = toolExecutionService;
    }

    /**
     * Create a new tool for a chatbot
     */
    @PostMapping("/{chatbotId}")
    public ResponseEntity<ToolModel.Tool> createTool(
            @PathVariable Long chatbotId,
            @RequestBody ToolModel.Tool tool) {
        log.info("Creating tool for chatbotId: {}, funcName: {}", chatbotId, tool.getFuncNameKey());
        tool.setChatbotId(chatbotId);
        ToolModel.Tool savedTool = toolRepository.save(tool);
        return ResponseEntity.ok(savedTool);
    }

    /**
     * Get all tools for a chatbot
     */
    @GetMapping("/{chatbotId}")
    public ResponseEntity<List<ToolModel.Tool>> getToolsForChatbot(@PathVariable Long chatbotId) {
        log.info("Fetching tools for chatbotId: {}", chatbotId);
        List<ToolModel.Tool> tools = toolRepository.findByChatbotId(chatbotId);
        return ResponseEntity.ok(tools);
    }

    /**
     * Get a specific tool by ID
     */
    @GetMapping("/detail/{toolId}")
    public ResponseEntity<ToolModel.Tool> getTool(@PathVariable Long toolId) {
        log.info("Fetching tool: {}", toolId);
        ToolModel.Tool tool = toolRepository.findById(toolId)
                .orElseThrow(() -> new RuntimeException("Tool not found: " + toolId));
        return ResponseEntity.ok(tool);
    }

    /**
     * Update a tool
     */
    @PutMapping("/{toolId}")
    public ResponseEntity<ToolModel.Tool> updateTool(
            @PathVariable Long toolId,
            @RequestBody ToolModel.Tool tool) {
        log.info("Updating tool: {}", toolId);
        tool.setId(toolId);
        ToolModel.Tool updatedTool = toolRepository.save(tool);
        return ResponseEntity.ok(updatedTool);
    }

    /**
     * Delete a tool
     */
    @DeleteMapping("/{toolId}")
    public ResponseEntity<Void> deleteTool(@PathVariable Long toolId) {
        log.info("Deleting tool: {}", toolId);
        toolRepository.delete(toolId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Test execute a tool directly (for debugging)
     */
    @PostMapping("/{chatbotId}/execute")
    public ResponseEntity<ToolExecutionResult> executeTool(
            @PathVariable Long chatbotId,
            @RequestBody ToolModel.ToolExecutionRequest request) {
        log.info("Executing tool: {} for chatbotId: {}", request.getFuncNameKey(), chatbotId);
        ToolExecutionResult result = toolExecutionService.executeTool(chatbotId,"test", request);
        return ResponseEntity.ok(result);
    }
}