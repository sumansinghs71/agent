package com.chatbot.agent.service;

import com.chatbot.agent.config.DynamicDataSourceConfig;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.GuardrailModel;
import com.chatbot.agent.repository.ToolRepository;
import com.chatbot.agent.service.guardrails.GuardrailLogService;
import com.chatbot.agent.service.guardrails.RuntimeGuardrailsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolRepository toolRepository;
    private final DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RuntimeGuardrailsService runtimeGuardrailsService;
    private final GuardrailLogService guardrailLogService;

    public ToolExecutionService(ToolRepository toolRepository,
                                DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager,
                                RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                RuntimeGuardrailsService runtimeGuardrailsService,
                                GuardrailLogService guardrailLogService) {
        this.toolRepository = toolRepository;
        this.dataSourceManager = dataSourceManager;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.runtimeGuardrailsService = runtimeGuardrailsService;
        this.guardrailLogService = guardrailLogService;
    }

    public ToolModel.ToolExecutionResult executeTool(Long chatbotId,
                                                     ToolModel.ToolExecutionRequest request) {
        String requestId = MDC.get("requestId");
        long startTime = System.currentTimeMillis();

        log.info("[requestId={}] Executing tool: {}", requestId, request.getFuncNameKey());

        ToolModel.ToolExecutionResult result = new ToolModel.ToolExecutionResult();

        try {
            ToolModel.Tool tool = toolRepository.findByChatbotIdAndFuncName(
                    chatbotId, request.getFuncNameKey()
            ).orElseThrow(() -> new RuntimeException("Tool not found: " + request.getFuncNameKey()));

            Map<String, Object> params = validateAndPrepareParameters(tool, request.getParams());

            Object data;
            switch (tool.getFunctionType()) {
                case SQL:
                    data = executeSqlTool(tool, params, chatbotId);
                    break;
                case REST:
                    data = executeRestTool(tool, params, chatbotId);
                    break;
                case PYTHON:
                    data = executePythonTool(tool, params);
                    break;
                case JAVASCRIPT:
                    data = executeJavaScriptTool(tool, params);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported function type");
            }

            result.setSuccess(true);
            result.setData(data);
            result.setExecutionTimeMs((int)(System.currentTimeMillis() - startTime));

        } catch (SecurityException e) {
            log.error("[requestId={}] Blocked by guardrails: {}", requestId, e.getMessage());
            result.setSuccess(false);
            result.setError("Security check failed: " + e.getMessage());
            result.setExecutionTimeMs((int)(System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            log.error("[requestId={}] Tool execution failed", requestId, e);
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setExecutionTimeMs((int)(System.currentTimeMillis() - startTime));
        }

        return result;
    }

    private Object executeSqlTool(ToolModel.Tool tool, Map<String, Object> params,
                                  Long chatbotId) throws Exception {
        String requestId = MDC.get("requestId");

        // RUNTIME GUARDRAIL: Validate SQL
        GuardrailModel.GuardrailResult sqlValidation =
                runtimeGuardrailsService.validateSqlQuery(tool.getSqlQuery());

        if (!sqlValidation.isAllowed()) {
            guardrailLogService.logViolation(chatbotId, null,
                    GuardrailModel.GuardrailType.RUNTIME_CHECK,
                    sqlValidation, tool.getSqlQuery());
            throw new SecurityException("SQL validation failed: " +
                    sqlValidation.getViolation().getDescription());
        }

        log.info("[requestId={}] SQL validation passed", requestId);

        try (Connection conn = dataSourceManager.getConnection(tool.getDataSource())) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(
                    new SingleConnectionDataSource(conn, true));

            String sql = tool.getSqlQuery();
            List<Object> paramValues = new ArrayList<>();

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

            List<Map<String, Object>> result = jdbcTemplate.queryForList(
                    sb.toString(), paramValues.toArray());

            log.info("[requestId={}] SQL returned {} rows", requestId, result.size());
            return result;
        }
    }

    private Object executeRestTool(ToolModel.Tool tool, Map<String, Object> params,
                                   Long chatbotId) {
        String requestId = MDC.get("requestId");

        String url = replacePlaceholders(tool.getHttpPath(), params);

        // RUNTIME GUARDRAIL: Validate URL
        GuardrailModel.GuardrailResult urlValidation =
                runtimeGuardrailsService.validateApiUrl(url);

        if (!urlValidation.isAllowed()) {
            guardrailLogService.logViolation(chatbotId, null,
                    GuardrailModel.GuardrailType.RUNTIME_CHECK,
                    urlValidation, url);
            throw new SecurityException("URL validation failed: " +
                    urlValidation.getViolation().getDescription());
        }

        log.info("[requestId={}] URL validation passed", requestId);

        HttpHeaders headers = new HttpHeaders();
        if (tool.getHttpHeaders() != null) {
            for (Map.Entry<String, String> entry : tool.getHttpHeaders().entrySet()) {
                String value = replacePlaceholders(entry.getValue(), params);
                headers.add(entry.getKey(), value);
            }
        }

        String body = null;
        if (tool.getHttpBody() != null && !tool.getHttpBody().isEmpty()) {
            body = replacePlaceholders(tool.getHttpBody(), params);
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(tool.getHttpMethod()), entity, String.class);

            if (response.getBody() != null && !response.getBody().isEmpty()) {
                try {
                    return objectMapper.readValue(response.getBody(), Object.class);
                } catch (Exception e) {
                    return response.getBody();
                }
            }
            return Map.of("status", response.getStatusCode().value());
        } catch (Exception e) {
            throw new RuntimeException("REST API call failed: " + e.getMessage(), e);
        }
    }

    private Object executePythonTool(ToolModel.Tool tool, Map<String, Object> params) {
        throw new UnsupportedOperationException("Python execution not yet implemented");
    }

    private Object executeJavaScriptTool(ToolModel.Tool tool, Map<String, Object> params) {
        throw new UnsupportedOperationException("JavaScript execution not yet implemented");
    }

    private Map<String, Object> validateAndPrepareParameters(ToolModel.Tool tool,
                                                             Map<String, Object> providedParams) {
        Map<String, Object> params = new HashMap<>();
        if (providedParams != null) params.putAll(providedParams);
        if (tool.getParams() == null) return params;

        for (ToolModel.ToolParameter param : tool.getParams()) {
            String paramName = param.getParamNameKey();
            if (!params.containsKey(paramName)) {
                if (param.isRequired()) {
                    if (param.getDefaultValue() != null) {
                        params.put(paramName, param.getDefaultValue());
                    } else {
                        throw new IllegalArgumentException("Required parameter missing: " + paramName);
                    }
                }
            }
        }
        return params;
    }

    private String replacePlaceholders(String template, Map<String, Object> params) {
        if (template == null) return null;

        String result = template;
        Pattern pattern = Pattern.compile("\\{\\{\\$([a-zA-Z0-9_]+)\\}\\}");
        Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1);
            Object value = params.get(paramName);
            if (value != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value.toString()));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public List<ToolModel.Tool> getToolsForChatbot(Long chatbotId) {
        return toolRepository.findByChatbotId(chatbotId);
    }
}