package com.chatbot.agent.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ToolModel {

    @Data
    public static class Tool {
        private Long id;
        private Long chatbotId;
        private String funcNameKey;
        private String label;
        private String prompt;
        private List<ToolParameter> params;
        private FunctionType functionType;
        private String dataSource;
        private String sqlQuery;
        private String httpMethod;
        private String httpPath;
        private Map<String, String> httpHeaders;
        private String httpBody;
        private Integer timeout;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ToolParameter {
        private String paramNameKey;
        private String paramType;
        private String paramDescription;
        private boolean required;
        private String defaultValue;
    }

    @Data
    public static class ColumnDefinition {
        private String columnId;
        private String label;
        private String type;
    }

    @Data
    public static class ToolExecutionLog {
        private Long id;
        private Long toolId;
        private Long chatbotId;
        private String sessionId;
        private Map<String, Object> inputParams;
        private String outputResult;
        private ExecutionStatus status;
        private String errorMessage;
        private Integer executionTimeMs;
        private LocalDateTime executedAt;
    }

    @Data
    public static class ToolExecutionRequest {
        private String funcNameKey;
        private Map<String, Object> params;
    }

    @Data
    public static class ToolExecutionResult {
        private boolean success;
        private Object data;
        private String error;
        private Integer executionTimeMs;
    }

    public enum FunctionType {
        SQL, REST, PYTHON, JAVASCRIPT
    }

    public enum ExecutionStatus {
        SUCCESS, FAILED, TIMEOUT
    }
}
