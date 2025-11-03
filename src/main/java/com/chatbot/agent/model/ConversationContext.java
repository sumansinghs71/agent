package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ConversationContext - Manages conversation history and context
 *
 * This class maintains the message history for a conversation session
 * and provides methods to add messages and retrieve context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContext {

    /**
     * Unique identifier for this conversation session
     */
    private String conversationId;

    /**
     * Chatbot ID this conversation belongs to
     */
    private Long chatbotId;

    /**
     * User ID who owns this conversation
     */
    private String userId;

    /**
     * List of messages in this conversation
     */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /**
     * Timestamp when conversation was created
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when conversation was last updated
     */
    private LocalDateTime updatedAt;

    /**
     * Maximum number of messages to retain in context
     */
    @Builder.Default
    private int maxHistorySize = 20;

    /**
     * Add a user message to the conversation
     */
    public void addUserMessage(String content) {
        messages.add(Message.builder()
                .role(MessageRole.USER)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build());

        updatedAt = LocalDateTime.now();
        pruneHistory();
    }

    /**
     * Add an assistant message to the conversation
     */
    public void addAssistantMessage(String content) {
        messages.add(Message.builder()
                .role(MessageRole.ASSISTANT)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build());

        updatedAt = LocalDateTime.now();
        pruneHistory();
    }

    /**
     * Add a system message to the conversation
     */
    public void addSystemMessage(String content) {
        messages.add(Message.builder()
                .role(MessageRole.SYSTEM)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build());

        updatedAt = LocalDateTime.now();
        pruneHistory();
    }

    /**
     * Get the conversation history as a formatted string
     */
    public String getHistoryAsString() {
        StringBuilder history = new StringBuilder();

        for (Message message : messages) {
            history.append(message.getRole().toString())
                    .append(": ")
                    .append(message.getContent())
                    .append("\n\n");
        }

        return history.toString();
    }

    /**
     * Get recent messages (last N messages)
     */
    public List<Message> getRecentMessages(int count) {
        int size = messages.size();
        int fromIndex = Math.max(0, size - count);
        return new ArrayList<>(messages.subList(fromIndex, size));
    }

    /**
     * Prune history if it exceeds max size
     * Keeps the most recent messages
     */
    private void pruneHistory() {
        if (messages.size() > maxHistorySize) {
            // Keep the most recent messages
            int excess = messages.size() - maxHistorySize;
            messages.subList(0, excess).clear();
        }
    }

    /**
     * Clear all messages
     */
    public void clear() {
        messages.clear();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Get the number of messages in the conversation
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * Individual message in the conversation
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private MessageRole role;
        private String content;
        private LocalDateTime timestamp;
    }

    /**
     * Message roles in the conversation
     */
    public enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }
}
