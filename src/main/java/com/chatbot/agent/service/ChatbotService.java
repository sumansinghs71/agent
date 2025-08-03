package com.chatbot.agent.service;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.repository.ChatbotRepository;
import com.chatbot.agent.repository.DataSourceRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatbotService {

    private final ChatbotRepository chatbotRepository;
    private final DataSourceRepository dataSourceRepository;

    public ChatbotService(ChatbotRepository chatbotRepository,
                          DataSourceRepository dataSourceRepository) {
        this.chatbotRepository = chatbotRepository;
        this.dataSourceRepository = dataSourceRepository;
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
}
