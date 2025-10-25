package com.chatbot.agent.service;

import com.chatbot.agent.controller.ChatbotController;
import com.chatbot.agent.model.Model;
import com.chatbot.agent.model.CitationModel;
import com.chatbot.agent.repository.ChatbotRepository;
import com.chatbot.agent.repository.DataSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ChatbotService {

    private final ChatbotRepository chatbotRepository;
    private final DataSourceRepository dataSourceRepository;
    private final ReasoningAgentService reasoningAgentService;
    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);

    public ChatbotService(ChatbotRepository chatbotRepository,
                          DataSourceRepository dataSourceRepository,
                          ReasoningAgentService reasoningAgentService) {
        this.chatbotRepository = chatbotRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.reasoningAgentService = reasoningAgentService;
    }

    @Transactional
    public Model.Chatbot createChatbot(Model.Chatbot chatbot) {
        chatbotRepository.save(chatbot);

        if (chatbot.getDataSources() != null) {
            for (Model.DataSource dataSource : chatbot.getDataSources()) {
                dataSource.setChatbotId(chatbot.getId());
                dataSourceRepository.save(dataSource);
            }
        }

        return chatbot;
    }

    public List<Model.Chatbot> getAllChatbots() {
        List<Model.Chatbot> chatbots = chatbotRepository.findAll();

        for (Model.Chatbot chatbot : chatbots) {
            chatbot.setDataSources(
                    dataSourceRepository.findByChatbotId(chatbot.getId())
            );
        }

        return chatbots;
    }

    public Model.Chatbot getChatbot(Long id) {
        Model.Chatbot chatbot = chatbotRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Chatbot not found with id: " + id));

        chatbot.setDataSources(
                dataSourceRepository.findByChatbotId(chatbot.getId())
        );

        return chatbot;
    }

    @Transactional
    public void deleteChatbot(Long id) {
        dataSourceRepository.deleteByChatbotId(id);
        chatbotRepository.delete(id);
    }

    @Transactional
    public Model.Chatbot updateChatbot(Long id, Model.Chatbot updatedChatbot) {
        Model.Chatbot existing = getChatbot(id);
        existing.setName(updatedChatbot.getName());
        chatbotRepository.update(existing);
        return existing;
    }

    /**
     * Handle chat and return text response (backward compatibility)
     */
    public String handleChat(Long chatbotId, String message) {
        log.info("Handling chat for chatbotId: {} with reasoning agent", chatbotId);
        return reasoningAgentService.processQuery(chatbotId, message);
    }

    /**
     * Handle chat and return structured response with citations
     */
    public CitationModel.ResponseWithCitations handleChatWithCitations(Long chatbotId, String message) {
        log.info("Handling chat with citations for chatbotId: {}", chatbotId);
        return reasoningAgentService.processQueryWithCitations(chatbotId, message);
    }

    @Transactional
    public Model.Chatbot updateInstructions(Long chatbotId,
                                            ChatbotController.InstructionUpdateRequest request) {
        Model.Chatbot chatbot = getChatbot(chatbotId);

        if (request.getSystemInstruction() != null) {
            chatbot.setSystemInstruction(request.getSystemInstruction());
        }

        if (request.getUserInstruction() != null) {
            chatbot.setUserInstruction(request.getUserInstruction());
        }

        if (request.getEnabled() != null) {
            chatbot.setInstructionEnabled(request.getEnabled());
        }

        chatbotRepository.update(chatbot);
        return chatbot;
    }
}