package com.chatbot.agent.service;

import com.chatbot.agent.controller.ChatbotController;
import com.chatbot.agent.model.Model;
import com.chatbot.agent.repository.ChatbotRepository;
import com.chatbot.agent.repository.DataSourceRepository;
import org.springframework.dao.EmptyResultDataAccessException;
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
//    private final AiRouterService aiRouterService;
//    private final VectorStoreService vectorStoreService;
//    private final AzureSearchService azureSearchService;
    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);

    public ChatbotService(ChatbotRepository chatbotRepository,
                          DataSourceRepository dataSourceRepository,
                          ReasoningAgentService reasoningAgentService
//                          AiRouterService aiRouterService,
//                          VectorStoreService vectorStoreService,
//                          AzureSearchService azureSearchService
    ) {
        this.chatbotRepository = chatbotRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.reasoningAgentService = reasoningAgentService;
//        this.aiRouterService = aiRouterService;
//        this.vectorStoreService = vectorStoreService;
//        this.azureSearchService = azureSearchService;
    }

    /**
     * Creates a new chatbot with associated data sources
     *
     * @param chatbot The chatbot to create
     * @return The created chatbot with generated ID
     */
    @Transactional
    public Model.Chatbot createChatbot(Model.Chatbot chatbot) {
        // Save chatbot to get generated ID
        chatbotRepository.save(chatbot);

        // Save associated data sources
        if (chatbot.getDataSources() != null) {
            for (Model.DataSource dataSource : chatbot.getDataSources()) {
                dataSource.setChatbotId(chatbot.getId());
                dataSourceRepository.save(dataSource);
            }
        }

        return chatbot;
    }

    /**
     * Retrieves all chatbots from the database
     *
     * @return List of all chatbots
     */
    public List<Model.Chatbot> getAllChatbots() {
        List<Model.Chatbot> chatbots = chatbotRepository.findAll();

        // Load data sources for each chatbot
        for (Model.Chatbot chatbot : chatbots) {
            chatbot.setDataSources(
                    dataSourceRepository.findByChatbotId(chatbot.getId())
            );
        }

        return chatbots;
    }

    /**
     * Retrieves a specific chatbot by ID
     *
     * @param id Chatbot ID
     * @return The requested chatbot
     * @throws RuntimeException if chatbot not found
     */
    public Model.Chatbot getChatbot(Long id) {
        Model.Chatbot chatbot = chatbotRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Chatbot not found with id: " + id));

        // Load associated data sources
        chatbot.setDataSources(
                dataSourceRepository.findByChatbotId(chatbot.getId())
        );

        return chatbot;
    }

    /**
     * Deletes a chatbot and all its associated resources
     *
     * @param id Chatbot ID to delete
     */
    @Transactional
    public void deleteChatbot(Long id) {
        // Delete associated data sources first
        dataSourceRepository.deleteByChatbotId(id);

        // Then delete the chatbot
        chatbotRepository.delete(id);
    }

    /**
     * Updates an existing chatbot
     *
     * @param id Chatbot ID to update
     * @param updatedChatbot Updated chatbot data
     * @return The updated chatbot
     */
    @Transactional
    public Model.Chatbot updateChatbot(Long id, Model.Chatbot updatedChatbot) {
        Model.Chatbot existing = getChatbot(id);

        // Update properties
        existing.setName(updatedChatbot.getName());

        // Save updated chatbot
        chatbotRepository.update(existing);

        return existing;
    }

    /**
     * Handles chat logic based on the chatbot ID and message
     *
     * @param chatbotId The ID of the chatbot
     * @param message   The message to process
     * @return The response from the chatbot
     */
//    public String handleChat(Long chatbotId, String message) {
//        log.info("Handling chat for chatbotId: {}", chatbotId);
//        Model.Chatbot chatbot = getChatbot(chatbotId);
//        if (chatbot.getModelType() == Model.ModelType.LLAMA) {
//            log.info("Using LLAMA flow for chatbotId: {}", chatbotId);
//            return vectorStoreService.searchAndGenerateResponse(chatbotId, message);
//        } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
//            log.info("Using Azure OpenAI flow for chatbotId: {}", chatbotId);
//            // Get top 5 relevant chunks from Azure Search
//            var contextChunks = azureSearchService.searchRelevantChunks(chatbotId, message, 5);
//            StringBuilder context = new StringBuilder();
//            for (String chunk : contextChunks) {
//                context.append(chunk).append("\n\n");
//            }
//            return aiRouterService.callAzureOpenAiWithContext(message, context.toString());
//        } else {
//            log.warn("Unsupported chatbot type for chatbotId: {}", chatbotId);
//            return "Unsupported chatbot type";
//        }
//    }

    /**
     * Main chat handler - uses reasoning agent to decide action
     */
    public String handleChat(Long chatbotId, String message) {
        log.info("Handling chat for chatbotId: {} with reasoning agent", chatbotId);

        // Delegate to reasoning agent
        return reasoningAgentService.processQuery(chatbotId, message);
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
