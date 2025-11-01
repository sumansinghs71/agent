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

        // REST API fields
        private String httpMethod;
        private String httpPath;
        private Map<String, String> httpHeaders;
        private String httpBody;

        // SQL fields
        private String sqlQuery;
        private String dataSource;

        // PYTHON fields (NEW)
        private String pythonCode;
        private String pythonVersion; // "3.x" or specific version
        private List<String> allowedModules; // Whitelist of allowed Python modules

        // JAVASCRIPT fields (NEW)
        private String jsCode;
        private String jsEngine; // "nashorn" or "graalvm"

        // Common fields
        private Long timeout;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ToolParameter {
        private String paramNameKey;
        private String paramDescription;
        private String paramType; // string, integer, boolean, float, array, object
        private boolean required;
        private Object defaultValue;
        private String validation; // regex or validation rules
    }

    /**
     * Function types supported by the system
     */
    public enum FunctionType {
        REST,       // HTTP API call
        SQL,        // Database query
        PYTHON,     // Python code execution (NEW)
        JAVASCRIPT  // JavaScript code execution (NEW)
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
        private Long executionTimeMs;
        private Map<String, Object> metadata;
    }

    /**
     * Python-specific configuration
     */
    @Data
    public static class PythonToolConfig {
        private String interpreterPath; // Path to Python interpreter
        private List<String> allowedModules; // json, math, datetime, re
        private Map<String, String> environmentVars;
        private boolean enableNumpy;
        private boolean enablePandas;
        private int maxMemoryMB; // Memory limit
    }

    /**
     * JavaScript-specific configuration
     */
    @Data
    public static class JavaScriptToolConfig {
        private String engine; // "nashorn" or "graalvm"
        private boolean enableConsole; // Allow console.log
        private Map<String, Object> globalVariables;
        private int maxStackSize;
    }
}
