package com.chatbot.agent.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Model {

    @Data
    public static class Chatbot {
        private Long id;
        private String name;
        private ModelType modelType;
        private LocalDateTime createdAt;
        private List<DataSource> dataSources = new ArrayList<>();
    }

    @Data
    public static class DataSource {
        private Long id;
        private Long chatbotId;
        private SourceType sourceType;
        private String config; // JSON string
    }

    @Data
    public static class Document {
        private Long id;
        private Long chatbotId;
        private String fileName;
        private String filePath;
        private DocumentStatus status;
        private LocalDateTime createdAt;
    }

    @Data
    public static class ChatSession {
        private Long id;
        private Long chatbotId;
        private String sessionId;
        private LocalDateTime createdAt;
        private LocalDateTime lastActivity;
    }

    @Data
    public static class ChatMessage {
        private Long id;
        private String sessionId;
        private String message;
        private SenderType sender;
        private LocalDateTime timestamp;
    }

    public enum ModelType {
        AZURE_OPENAI, LLAMA
    }

    public enum SourceType {
        REST_API, PYTHON_CODE, DOCUMENT
    }

    public enum DocumentStatus {
        UPLOADED, PROCESSING, INDEXED, FAILED
    }

    public enum SenderType {
        USER, AI
    }
}