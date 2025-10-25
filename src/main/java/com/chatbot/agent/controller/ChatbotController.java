package com.chatbot.agent.controller;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.model.CitationModel;
import com.chatbot.agent.service.ChatbotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.Data;

import java.util.List;

@RestController
@RequestMapping("/api/chatbots")
public class ChatbotController {

    private final ChatbotService chatbotService;
    private static final Logger log = LoggerFactory.getLogger(ChatbotController.class);

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @PostMapping
    public ResponseEntity<Model.Chatbot> createChatbot(@RequestBody Model.Chatbot chatbot) {
        return ResponseEntity.ok(chatbotService.createChatbot(chatbot));
    }

    @GetMapping
    public ResponseEntity<List<Model.Chatbot>> getAllChatbots() {
        return ResponseEntity.ok(chatbotService.getAllChatbots());
    }

    /**
     * Chat endpoint - returns text response with inline citations
     */
    @PostMapping("/{chatbotId}/chat")
    public ResponseEntity<String> chat(
            @PathVariable Long chatbotId,
            @RequestBody String message) {
        log.info("Received chat request for chatbotId: {}", chatbotId);
        String response = chatbotService.handleChat(chatbotId, message);
        return ResponseEntity.ok(response);
    }

    /**
     * Chat endpoint with structured citations - returns JSON with citations
     */
    @PostMapping("/{chatbotId}/chat-with-citations")
    public ResponseEntity<CitationModel.ResponseWithCitations> chatWithCitations(
            @PathVariable Long chatbotId,
            @RequestBody ChatRequest request) {
        log.info("Received chat-with-citations request for chatbotId: {}", chatbotId);
        CitationModel.ResponseWithCitations response =
                chatbotService.handleChatWithCitations(chatbotId, request.getMessage());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{chatbotId}/instructions")
    public ResponseEntity<Model.Chatbot> updateInstructions(
            @PathVariable Long chatbotId,
            @RequestBody InstructionUpdateRequest request) {
        log.info("Updating instructions for chatbot: {}", chatbotId);
        Model.Chatbot updated = chatbotService.updateInstructions(chatbotId, request);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{chatbotId}/instructions")
    public ResponseEntity<InstructionResponse> getInstructions(@PathVariable Long chatbotId) {
        log.info("Getting instructions for chatbot: {}", chatbotId);
        Model.Chatbot chatbot = chatbotService.getChatbot(chatbotId);

        InstructionResponse response = new InstructionResponse();
        response.setSystemInstruction(chatbot.getSystemInstruction());
        response.setUserInstruction(chatbot.getUserInstruction());
        response.setEnabled(chatbot.getInstructionEnabled());

        return ResponseEntity.ok(response);
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