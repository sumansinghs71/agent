package com.chatbot.agent.controller;


import com.chatbot.agent.model.Model;
import com.chatbot.agent.service.AiRouterService;
import com.chatbot.agent.service.ChatbotService;
import com.chatbot.agent.service.VectorStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/chatbots")
public class ChatbotController {

    private final ChatbotService chatbotService;
    private final AiRouterService aiRouterService;
    private final VectorStoreService vectorStoreService;

    public ChatbotController(ChatbotService chatbotService,
                             AiRouterService aiRouterService, VectorStoreService vectorStoreService) {
        this.chatbotService = chatbotService;
        this.aiRouterService = aiRouterService;
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping
    public ResponseEntity<Model.Chatbot> createChatbot(@RequestBody Model.Chatbot chatbot) {
        return ResponseEntity.ok(chatbotService.createChatbot(chatbot));
    }

    @GetMapping
    public ResponseEntity<List<Model.Chatbot>> getAllChatbots() {
        return ResponseEntity.ok(chatbotService.getAllChatbots());
    }

    @PostMapping("/{chatbotId}/chat")
    public ResponseEntity<String> chat(
            @PathVariable Long chatbotId,
            @RequestBody String message) {

        Model.Chatbot chatbot = chatbotService.getChatbot(chatbotId);

        if (chatbot.getModelType() == Model.ModelType.LLAMA) {
            // Use document-based response
            String response = vectorStoreService.searchAndGenerateResponse(chatbotId, message);
            return ResponseEntity.ok(response);
        } else {
            // Azure OpenAI flow (to be implemented later)
            return ResponseEntity.ok("Azure flow not implemented yet");
        }
    }
}