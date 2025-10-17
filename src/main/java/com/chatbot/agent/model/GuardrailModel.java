package com.chatbot.agent.model;

import lombok.Data;
import java.time.LocalDateTime;

public class GuardrailModel {

    @Data
    public static class GuardrailResult {
        private boolean allowed;
        private GuardrailViolation violation;
        private String sanitizedInput;
        private double riskScore; // 0.0 to 1.0
        private String reasoning;
    }

    @Data
    public static class GuardrailViolation {
        private ViolationType type;
        private String description;
        private SeverityLevel severity;
        private String detectedContent;
        private String recommendation;
    }

    @Data
    public static class GuardrailConfig {
        private Long chatbotId;
        private boolean enableInputGuardrails;
        private boolean enableOutputGuardrails;
        private boolean enablePiiDetection;
        private boolean enableToxicityFilter;
        private boolean enableJailbreakDetection;
        private boolean enableHallucinationDetection;
        private double toxicityThreshold; // 0.0 to 1.0
        private double hallucinationThreshold;
        private int maxTokens;
        private int maxToolExecutionTime; // seconds
    }

    @Data
    public static class GuardrailLog {
        private Long id;
        private Long chatbotId;
        private String sessionId;
        private GuardrailType guardrailType;
        private ViolationType violationType;
        private boolean blocked;
        private String inputText;
        private String sanitizedText;
        private double riskScore;
        private String metadata; // JSON
        private LocalDateTime timestamp;
    }

    public enum GuardrailType {
        INPUT_VALIDATION,
        OUTPUT_VALIDATION,
        RUNTIME_CHECK
    }

    public enum ViolationType {
        PII_DETECTED,
        TOXIC_CONTENT,
        JAILBREAK_ATTEMPT,
        PROMPT_INJECTION,
        SQL_INJECTION,
        SSRF_ATTEMPT,
        HALLUCINATION,
        OFF_TOPIC,
        EXCESSIVE_LENGTH,
        RATE_LIMIT_EXCEEDED,
        RESOURCE_LIMIT_EXCEEDED,
        UNSAFE_TOOL_CALL,
        DANGEROUS_SQL_KEYWORD,
        UNAUTHORIZED_API_ACCESS
    }

    public enum SeverityLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}