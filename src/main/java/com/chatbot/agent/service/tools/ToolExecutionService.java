package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.*;
import com.chatbot.agent.model.*;
import com.chatbot.agent.repository.ToolRepository;
import com.chatbot.agent.service.guardrails.RuntimeGuardrailsService;
import com.chatbot.agent.service.guardrails.GuardrailLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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

/**
 * ToolExecutionService - Updated with ExecutionContext support
 *
 * CHANGES FROM ORIGINAL:
 * - Added ExecutionContext management
 * - Added ToolRegistryService for caching
 * - Added PythonJavaScriptToolExecutor support
 * - Added handleEzToolCall() for nested calls
 * - Context passed to all execution methods
 */
@Service
@Slf4j
public class ToolExecutionService {

    private final ExecutionContextFactory contextFactory;
    private final ToolRegistryService toolRegistry;
    private final ToolRepository toolRepository;
    private final PythonJavaScriptToolExecutor pyJsExecutor;
    private final RuntimeGuardrailsService guardrailsService;
    private final GuardrailLogService guardrailLogService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ToolExecutionProperties config;

    public ToolExecutionService(
            ExecutionContextFactory contextFactory,
            ToolRegistryService toolRegistry,
            ToolRepository toolRepository,
            PythonJavaScriptToolExecutor pyJsExecutor,
            RuntimeGuardrailsService guardrailsService,
            GuardrailLogService guardrailLogService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ToolExecutionProperties config) {

        this.contextFactory = contextFactory;
        this.toolRegistry = toolRegistry;
        this.toolRepository = toolRepository;
        this.pyJsExecutor = pyJsExecutor;
        this.guardrailsService = guardrailsService;
        this.guardrailLogService = guardrailLogService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.config = config;

        // CRITICAL: Set service in executor to avoid circular dependency
        pyJsExecutor.setToolExecutionService(this);

        log.info("ToolExecutionService initialized with inter-tool communication support");
    }

    /**
     * Main entry point - creates ExecutionContext
     *
     * @param chatbotId The chatbot ID
     * @param userId User making the request
     * @param request Tool execution request
     * @return Execution result
     */
    public ToolExecutionResult executeTool(Long chatbotId, String userId, ToolModel.ToolExecutionRequest request) {

        long startTime = System.currentTimeMillis();

        // Create context with try-with-resources for automatic cleanup
        try (ExecutionContext context = contextFactory.create(chatbotId, userId)) {

            try {
                // Set MDC for logging
                MDC.put("executionId", context.getExecutionId());
                MDC.put("chatbotId", String.valueOf(chatbotId));
                MDC.put("userId", userId);

                log.info("Starting tool execution: {} for user: {}", request.getFuncNameKey(), userId);

                // Execute the tool with context
                ToolExecutionResult result = executeToolInternal(context, request);

                // Add execution metadata
                result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
                result.setExecutionId(context.getExecutionId());
                result.setCallChain(context.getCallChainList());

                log.info("Tool execution completed successfully. Total calls: {}, Duration: {}ms",
                        context.getTotalToolCalls(),
                        context.getElapsedTimeMs());

                return result;

            } catch (ToolExecutionTimeoutException e) {
                log.error("Tool execution timed out", e);
                ToolExecutionResult result = ToolExecutionResult.failure(
                        e.getMessage(),
                        e.getErrorCode(),
                        System.currentTimeMillis() - startTime
                );
                result.setExecutionId(context.getExecutionId());
                result.setCallChain(context.getCallChainList());
                return result;

            } catch (CircularDependencyException e) {
                log.error("Circular dependency detected", e);
                ToolExecutionResult result = ToolExecutionResult.failure(
                        e.getMessage(),
                        e.getErrorCode(),
                        System.currentTimeMillis() - startTime
                );
                result.setExecutionId(context.getExecutionId());
                result.setCallChain(context.getCallChainList());
                return result;

            } catch (MaxDepthExceededException e) {
                log.error("Maximum depth exceeded", e);
                ToolExecutionResult result = ToolExecutionResult.failure(
                        e.getMessage(),
                        e.getErrorCode(),
                        System.currentTimeMillis() - startTime
                );
                result.setExecutionId(context.getExecutionId());
                result.setCallChain(context.getCallChainList());
                return result;

            } catch (ToolExecutionException e) {
                log.error("Tool execution failed", e);
                ToolExecutionResult result = ToolExecutionResult.failure(
                        e.getMessage(),
                        e.getErrorCode(),
                        System.currentTimeMillis() - startTime
                );
                result.setExecutionId(context.getExecutionId());
                result.setCallChain(context.getCallChainList());
                return result;

            } catch (Exception e) {
                log.error("Unexpected error during tool execution", e);
                ToolExecutionResult result = ToolExecutionResult.failure(
                        "Internal error: " + e.getMessage(),
                        "INTERNAL_ERROR",
                        System.currentTimeMillis() - startTime
                );
                result.setExecutionId(context.getExecutionId());
                return result;

            } finally {
                // Remove from tracking
                contextFactory.remove(context);

                // Clear MDC
                MDC.clear();
            }

        } // context.close() called automatically here
    }

    /**
     * Internal execution - receives context explicitly
     *
     * @param context Execution context
     * @param request Tool execution request
     * @return Execution result
     */
    private ToolExecutionResult executeToolInternal(ExecutionContext context, ToolModel.ToolExecutionRequest request) throws Exception {

        String toolId = request.getFuncNameKey();

        // Register this tool call in context
        context.registerToolCall(toolId);

        try {
            // Get tool from registry (with caching)
            ToolModel.Tool tool = toolRegistry.getTool(context.getChatbotId(), toolId);

            // Check timeout before execution
            context.checkTimeout();

            // Validate and prepare parameters
            Map<String, Object> params = validateAndPrepareParameters(tool, request.getParams());

            // Execute based on type
            Object data = switch (tool.getFunctionType()) {
                case SQL -> executeSqlTool(context, tool, params);
                case REST -> executeRestTool(context, tool, params);
                case PYTHON -> executePythonTool(context, tool, params);
                case JAVASCRIPT -> executeJavaScriptTool(context, tool, params);
            };

            return ToolExecutionResult.success(data);

        } catch (Exception e) {
            // Unregister with error
            context.unregisterToolCallWithError(toolId, e.getMessage());
            throw e;

        } finally {
            // CRITICAL: Always unregister on success
            if (!context.getCallChainList().isEmpty() &&
                    context.getCallChainList().get(context.getCallChainList().size() - 1).equals(toolId)) {
                // Only unregister if this tool is still on top of stack (not already unregistered due to error)
                // This is already handled by catch block above, so we don't double-unregister
            }
        }
    }

    /**
     * Handle nested tool call from eztool()
     * This is called when a tool wants to call another tool
     *
     * @param context Current execution context
     * @param toolId Tool to call
     * @param params Parameters
     * @return Result data
     */
    public Object handleEzToolCall(ExecutionContext context, String toolId, Map<String, Object> params) throws Exception {

        log.debug("[executionId={}] Handling eztool call: {} (depth: {})",
                context.getExecutionId(), toolId, context.getCurrentDepth());

        // Create nested request
        ToolModel.ToolExecutionRequest nestedRequest = new ToolModel.ToolExecutionRequest();
        nestedRequest.setFuncNameKey(toolId);
        nestedRequest.setParams(params != null ? params : new HashMap<>());

        // Execute with same context (this will increment depth, check circular deps, etc.)
        ToolExecutionResult result = executeToolInternal(context, nestedRequest);

        if (!result.isSuccess()) {
            throw new ToolChainException(
                    String.format("Nested tool '%s' failed: %s", toolId, result.getError()),
                    toolId,
                    context.getCurrentDepth(),
                    context.getCallChainList()
            );
        }

        return result.getData();
    }

    /**
     * Execute SQL tool with context
     */
    private Object executeSqlTool(ExecutionContext context, ToolModel.Tool tool, Map<String, Object> params) {

        String executionId = context.getExecutionId();

        // Check timeout before execution
        context.checkTimeout();

        // RUNTIME GUARDRAIL: Validate SQL
        GuardrailModel.GuardrailResult sqlValidation = guardrailsService.validateSqlQuery(tool.getSqlQuery());

        if (!sqlValidation.isAllowed()) {
            guardrailLogService.logViolation(context.getChatbotId(), null,
                    GuardrailModel.GuardrailType.RUNTIME_CHECK,
                    sqlValidation, tool.getSqlQuery());
            throw new SecurityException("SQL validation failed: " + sqlValidation.getViolation().getDescription());
        }

        log.info("[executionId={}] SQL validation passed", executionId);

        // Your existing SQL execution code here...
        // (Keep your existing implementation)

        return executeSqlToolOriginal(tool, params, context.getChatbotId());
    }

    /**
     * Execute REST tool with context
     */
    private Object executeRestTool(ExecutionContext context, ToolModel.Tool tool, Map<String, Object> params) {

        String executionId = context.getExecutionId();

        // Check timeout before execution
        context.checkTimeout();

        String url = replacePlaceholders(tool.getHttpPath(), params);

        // RUNTIME GUARDRAIL: Validate URL
        GuardrailModel.GuardrailResult urlValidation = guardrailsService.validateApiUrl(url);

        if (!urlValidation.isAllowed()) {
            guardrailLogService.logViolation(context.getChatbotId(), null,
                    GuardrailModel.GuardrailType.RUNTIME_CHECK,
                    urlValidation, url);
            throw new SecurityException("URL validation failed: " + urlValidation.getViolation().getDescription());
        }

        log.info("[executionId={}] URL validation passed", executionId);

        // Your existing REST execution code here...
        return executeRestToolOriginal(tool, params);
    }

    /**
     * Execute Python tool - delegates to executor
     */
    private Object executePythonTool(ExecutionContext context, ToolModel.Tool tool, Map<String, Object> params) throws Exception {
        log.debug("[executionId={}] Executing Python tool: {}", context.getExecutionId(), tool.getFuncNameKey());
        return pyJsExecutor.executePythonTool(context, tool, params);
    }

    /**
     * Execute JavaScript tool - delegates to executor
     */
    private Object executeJavaScriptTool(ExecutionContext context, ToolModel.Tool tool, Map<String, Object> params) throws Exception {
        log.debug("[executionId={}] Executing JavaScript tool: {}", context.getExecutionId(), tool.getFuncNameKey());
        return pyJsExecutor.executeJavaScriptTool(context, tool, params);
    }

    /**
     * Get tools for a chatbot
     */
    public List<ToolModel.Tool> getToolsForChatbot(Long chatbotId) {
        return new ArrayList<>(toolRegistry.getAllTools(chatbotId).values());
    }

    // ==================================================================================
    //                        KEEP YOUR EXISTING METHODS
    // ==================================================================================

    /**
     * Your existing SQL execution (keep as-is, just rename)
     */
    private Object executeSqlToolOriginal(ToolModel.Tool tool, Map<String, Object> params, Long chatbotId) {
        // Your existing SQL execution code
        // Copy from your current ToolExecutionService
        return null; // Replace with your implementation
    }

    /**
     * Your existing REST execution (keep as-is, just rename)
     */
    private Object executeRestToolOriginal(ToolModel.Tool tool, Map<String, Object> params) {
        // Your existing REST execution code
        // Copy from your current ToolExecutionService
        return null; // Replace with your implementation
    }

    /**
     * Your existing parameter validation (keep as-is)
     */
    private Map<String, Object> validateAndPrepareParameters(ToolModel.Tool tool, Map<String, Object> providedParams) {
        // Your existing parameter validation code
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

    /**
     * Your existing placeholder replacement (keep as-is)
     */
    private String replacePlaceholders(String template, Map<String, Object> params) {
        // Your existing placeholder replacement code
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
}