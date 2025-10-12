package com.chatbot.agent.service;

import com.chatbot.agent.config.DynamicDataSourceConfig;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.repository.ToolRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolRepository toolRepository;
    private final DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ToolExecutionService(ToolRepository toolRepository,
                                DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager,
                                RestTemplate restTemplate,
                                ObjectMapper objectMapper) {
        this.toolRepository = toolRepository;
        this.dataSourceManager = dataSourceManager;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Main entry point for tool execution
     */
    public ToolModel.ToolExecutionResult executeTool(Long chatbotId,
                                                     ToolModel.ToolExecutionRequest request) {
        String requestId = MDC.get("requestId");
        long startTime = System.currentTimeMillis();

        log.info("[requestId={}] Executing tool: {} for chatbot: {}",
                requestId, request.getFuncNameKey(), chatbotId);

        ToolModel.ToolExecutionResult result = new ToolModel.ToolExecutionResult();

        try {
            // Find tool
            ToolModel.Tool tool = toolRepository.findByChatbotIdAndFuncName(
                    chatbotId, request.getFuncNameKey()
            ).orElseThrow(() -> new RuntimeException("Tool not found: " + request.getFuncNameKey()));

            // Validate parameters
            Map<String, Object> params = validateAndPrepareParameters(tool, request.getParams());

            // Execute based on function type
            Object data;
            switch (tool.getFunctionType()) {
                case SQL:
                    data = executeSqlTool(tool, params);
                    break;
                case REST:
                    data = executeRestTool(tool, params);
                    break;
                case PYTHON:
                    data = executePythonTool(tool, params);
                    break;
                case JAVASCRIPT:
                    data = executeJavaScriptTool(tool, params);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported function type: " +
                            tool.getFunctionType());
            }

            result.setSuccess(true);
            result.setData(data);
            result.setExecutionTimeMs((int)(System.currentTimeMillis() - startTime));

            log.info("[requestId={}] Tool execution successful: {}, time: {}ms",
                    requestId, request.getFuncNameKey(), result.getExecutionTimeMs());

        } catch (Exception e) {
            log.error("[requestId={}] Tool execution failed: {}", requestId,
                    request.getFuncNameKey(), e);
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setExecutionTimeMs((int)(System.currentTimeMillis() - startTime));
        }

        return result;
    }

    /**
     * Validates parameters and applies defaults
     */
    private Map<String, Object> validateAndPrepareParameters(ToolModel.Tool tool,
                                                             Map<String, Object> providedParams) {
        Map<String, Object> params = new HashMap<>();

        if (providedParams != null) {
            params.putAll(providedParams);
        }

        if (tool.getParams() == null || tool.getParams().isEmpty()) {
            return params;
        }

        for (ToolModel.ToolParameter param : tool.getParams()) {
            String paramName = param.getParamNameKey();

            if (!params.containsKey(paramName)) {
                if (param.isRequired()) {
                    if (param.getDefaultValue() != null) {
                        // Use default value
                        params.put(paramName, param.getDefaultValue());
                        log.debug("Using default value for parameter: {}", paramName);
                    } else {
                        throw new IllegalArgumentException(
                                "Required parameter missing: " + paramName
                        );
                    }
                }
            } else {
                // Validate type if needed
                validateParameterType(paramName, params.get(paramName), param.getParamType());
            }
        }

        return params;
    }

    /**
     * Validates parameter type
     */
    private void validateParameterType(String paramName, Object value, String expectedType) {
        if (value == null) return;

        switch (expectedType.toLowerCase()) {
            case "string":
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                            "Parameter " + paramName + " must be a string"
                    );
                }
                break;
            case "integer":
            case "int":
                if (!(value instanceof Integer) && !(value instanceof Long)) {
                    try {
                        Integer.parseInt(value.toString());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Parameter " + paramName + " must be an integer"
                        );
                    }
                }
                break;
            case "number":
            case "double":
            case "float":
                if (!(value instanceof Number)) {
                    try {
                        Double.parseDouble(value.toString());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Parameter " + paramName + " must be a number"
                        );
                    }
                }
                break;
            case "boolean":
                if (!(value instanceof Boolean)) {
                    String strValue = value.toString().toLowerCase();
                    if (!strValue.equals("true") && !strValue.equals("false")) {
                        throw new IllegalArgumentException(
                                "Parameter " + paramName + " must be a boolean"
                        );
                    }
                }
                break;
        }
    }

    /**
     * Executes SQL-based tool
     */
    private Object executeSqlTool(ToolModel.Tool tool, Map<String, Object> params) throws Exception {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing SQL tool on datasource: {}", requestId, tool.getDataSource());

        if (tool.getDataSource() == null || tool.getDataSource().isEmpty()) {
            throw new IllegalArgumentException("DataSource not specified for SQL tool");
        }

        // Get connection from dynamic datasource
        try (Connection conn = dataSourceManager.getConnection(tool.getDataSource())) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(
                    new SingleConnectionDataSource(conn, true)
            );

            // Replace named parameters in SQL
            String sql = tool.getSqlQuery();
            List<Object> paramValues = new ArrayList<>();

            // Convert named parameters (:paramName) to positional (?)
            Pattern pattern = Pattern.compile(":([a-zA-Z0-9_]+)");
            Matcher matcher = pattern.matcher(sql);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String paramName = matcher.group(1);
                if (!params.containsKey(paramName)) {
                    throw new IllegalArgumentException("Missing parameter: " + paramName);
                }
                paramValues.add(params.get(paramName));
                matcher.appendReplacement(sb, "?");
            }
            matcher.appendTail(sb);
            String processedSql = sb.toString();

            log.info("[requestId={}] Executing SQL: {}, params: {}", requestId, processedSql, paramValues);

            // Execute query
            List<Map<String, Object>> result = jdbcTemplate.queryForList(
                    processedSql,
                    paramValues.toArray()
            );

            log.info("[requestId={}] SQL execution returned {} rows", requestId, result.size());
            return result;
        }
    }

    /**
     * Executes REST API-based tool
     */
    private Object executeRestTool(ToolModel.Tool tool, Map<String, Object> params) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing REST tool: {} {}", requestId,
                tool.getHttpMethod(), tool.getHttpPath());

        // Replace parameters in URL
        String url = replacePlaceholders(tool.getHttpPath(), params);

        // Replace parameters in headers
        HttpHeaders headers = new HttpHeaders();
        if (tool.getHttpHeaders() != null) {
            for (Map.Entry<String, String> entry : tool.getHttpHeaders().entrySet()) {
                String value = replacePlaceholders(entry.getValue(), params);
                headers.add(entry.getKey(), value);
            }
        }

        // Replace parameters in body
        String body = null;
        if (tool.getHttpBody() != null && !tool.getHttpBody().isEmpty()) {
            body = replacePlaceholders(tool.getHttpBody(), params);
        }

        // Create HTTP entity
        HttpEntity<?> requestEntity = (body != null) ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.valueOf(tool.getHttpMethod()),
                requestEntity,
                String.class
            );
            log.info("[requestId={}] REST tool response status: {}", requestId, response.getStatusCode());
            if (response.getBody() == null) {
                return "";
            }
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null && contentType.getType().equalsIgnoreCase("application") && contentType.getSubtype().contains("json")) {
                try {
                    return objectMapper.readValue(response.getBody(), Object.class);
                } catch (Exception e) {
                    log.warn("[requestId={}] Failed to parse JSON response, returning raw string", requestId, e);
                    return response.getBody();
                }
            }
            return response.getBody();
        } catch (RestClientException e) {
            log.error("[requestId={}] REST tool execution failed: {}", requestId, e.getMessage(), e);
            throw new RuntimeException("Failed to execute REST tool: " + e.getMessage(), e);
        }
    }

    /**
     * Executes Python-based tool
     */
    private Object executePythonTool(ToolModel.Tool tool, Map<String, Object> params) {
        // TODO: Implement Python execution using Jython or external Python process
        // For now, throw unsupported exception
        throw new UnsupportedOperationException(
                "Python execution not yet implemented. " +
                        "Planned implementation: Use ProcessBuilder to execute Python scripts."
        );
    }

    /**
     * Executes JavaScript-based tool
     */
    private Object executeJavaScriptTool(ToolModel.Tool tool, Map<String, Object> params) {
        // TODO: Implement JavaScript execution using GraalVM or Nashorn
        // For now, throw unsupported exception
        throw new UnsupportedOperationException(
                "JavaScript execution not yet implemented. " +
                        "Planned implementation: Use GraalVM Polyglot API for JavaScript execution."
        );
    }

    /**
     * Replaces placeholders in template string with parameter values
     * Supports both {{$paramName}} and {paramName} formats
     */
    private String replacePlaceholders(String template, Map<String, Object> params) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        String result = template;

        // Replace {{$paramName}} format
        Pattern pattern1 = Pattern.compile("\\{\\{\\$([a-zA-Z0-9_]+)\\}\\}");
        Matcher matcher1 = pattern1.matcher(result);
        StringBuffer sb1 = new StringBuffer();

        while (matcher1.find()) {
            String paramName = matcher1.group(1);
            Object value = params.get(paramName);
            if (value != null) {
                matcher1.appendReplacement(sb1, Matcher.quoteReplacement(value.toString()));
            } else {
                log.warn("Parameter not found for placeholder: {}", paramName);
                matcher1.appendReplacement(sb1, Matcher.quoteReplacement("{{$" + paramName + "}}"));
            }
        }
        matcher1.appendTail(sb1);
        result = sb1.toString();

        // Replace {paramName} format (but not {{...}})
        Pattern pattern2 = Pattern.compile("(?<!\\{)\\{([a-zA-Z0-9_]+)\\}(?!\\})");
        Matcher matcher2 = pattern2.matcher(result);
        StringBuffer sb2 = new StringBuffer();

        while (matcher2.find()) {
            String paramName = matcher2.group(1);
            Object value = params.get(paramName);
            if (value != null) {
                matcher2.appendReplacement(sb2, Matcher.quoteReplacement(value.toString()));
            } else {
                log.warn("Parameter not found for placeholder: {}", paramName);
                matcher2.appendReplacement(sb2, Matcher.quoteReplacement("{" + paramName + "}"));
            }
        }
        matcher2.appendTail(sb2);

        return sb2.toString();
    }

    /**
     * Gets all tools for a chatbot
     */
    public List<ToolModel.Tool> getToolsForChatbot(Long chatbotId) {
        return toolRepository.findByChatbotId(chatbotId);
    }

    /**
     * Generates tool descriptions formatted for AI consumption
     */
    public String generateToolDescriptionsForAI(Long chatbotId) {
        List<ToolModel.Tool> tools = getToolsForChatbot(chatbotId);

        if (tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available Tools:\n\n");

        for (ToolModel.Tool tool : tools) {
            sb.append("Tool Name: ").append(tool.getFuncNameKey()).append("\n");
            sb.append("Description: ").append(tool.getPrompt()).append("\n");

            if (tool.getParams() != null && !tool.getParams().isEmpty()) {
                sb.append("Parameters:\n");
                for (ToolModel.ToolParameter param : tool.getParams()) {
                    sb.append("  - ").append(param.getParamNameKey())
                            .append(" (").append(param.getParamType()).append(")")
                            .append(param.isRequired() ? " [REQUIRED]" : " [OPTIONAL]")
                            .append(": ").append(param.getParamDescription()).append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Gets tool summary statistics for a chatbot
     */
    public Map<String, Object> getToolStatistics(Long chatbotId) {
        List<ToolModel.Tool> tools = getToolsForChatbot(chatbotId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total_tools", tools.size());
        stats.put("sql_tools", tools.stream()
                .filter(t -> t.getFunctionType() == ToolModel.FunctionType.SQL)
                .count());
        stats.put("rest_tools", tools.stream()
                .filter(t -> t.getFunctionType() == ToolModel.FunctionType.REST)
                .count());
        stats.put("python_tools", tools.stream()
                .filter(t -> t.getFunctionType() == ToolModel.FunctionType.PYTHON)
                .count());
        stats.put("javascript_tools", tools.stream()
                .filter(t -> t.getFunctionType() == ToolModel.FunctionType.JAVASCRIPT)
                .count());

        return stats;
    }

    /**
     * Tests tool connectivity without executing full query
     */
    public Map<String, Object> testToolConnection(Long toolId) {
        String requestId = MDC.get("requestId");
        Map<String, Object> result = new HashMap<>();

        try {
            ToolModel.Tool tool = toolRepository.findById(toolId)
                    .orElseThrow(() -> new RuntimeException("Tool not found: " + toolId));

            result.put("tool_name", tool.getFuncNameKey());
            result.put("function_type", tool.getFunctionType());

            switch (tool.getFunctionType()) {
                case SQL:
                    // Test database connection
                    try (Connection conn = dataSourceManager.getConnection(tool.getDataSource())) {
                        result.put("connection_status", "SUCCESS");
                        result.put("datasource", tool.getDataSource());
                    }
                    break;

                case REST:
                    // Test if URL is reachable (basic check)
                    result.put("connection_status", "CONFIGURED");
                    result.put("endpoint", tool.getHttpPath());
                    break;

                default:
                    result.put("connection_status", "NOT_TESTABLE");
            }

            result.put("success", true);

        } catch (Exception e) {
            log.error("[requestId={}] Tool connection test failed", requestId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
}
