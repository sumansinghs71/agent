package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.DynamicDataSourceConfig;
import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.*;
import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.*;
import com.chatbot.agent.repository.ToolRepository;
import com.chatbot.agent.service.guardrails.RuntimeGuardrailsService;
import com.chatbot.agent.service.guardrails.GuardrailLogService;
import com.chatbot.agent.service.policy.PolicyDecision;
import com.chatbot.agent.service.policy.ToolInvocationPolicy;
import com.chatbot.agent.service.policy.RestHeaderPolicy;
import com.chatbot.agent.security.InvocationPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ToolExecutionService - Updated with ExecutionContext support
 * <p>
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
    private final DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager;
    private final AgentMetrics metrics;
    private final ToolInvocationPolicy policy;

    public ToolExecutionService(
            ExecutionContextFactory contextFactory,
            ToolRegistryService toolRegistry,
            ToolRepository toolRepository,
            PythonJavaScriptToolExecutor pyJsExecutor,
            RuntimeGuardrailsService guardrailsService,
            GuardrailLogService guardrailLogService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ToolExecutionProperties config,
            DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager,
            AgentMetrics metrics,
            ToolInvocationPolicy policy) {

        this.metrics = metrics;
        this.policy = policy;
        this.contextFactory = contextFactory;
        this.toolRegistry = toolRegistry;
        this.toolRepository = toolRepository;
        this.pyJsExecutor = pyJsExecutor;
        this.guardrailsService = guardrailsService;
        this.guardrailLogService = guardrailLogService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.config = config;
        this.dataSourceManager = dataSourceManager;

        // CRITICAL: Set service in executor to avoid circular dependency
        pyJsExecutor.setToolExecutionService(this);

        log.info("ToolExecutionService initialized with inter-tool communication support");
    }

    /**
     * Main entry point - creates ExecutionContext
     *
     * @param chatbotId The chatbot ID
     * @param userId    User making the request
     * @param request   Tool execution request
     * @return Execution result
     */
    /**
     * @deprecated a bare user id carries no roles, so the resulting principal can invoke nothing and
     * every call will be denied with {@code INSUFFICIENT_AUTHORITY}. Retained only so existing tests
     * and callers fail closed rather than failing to compile into an unchecked path. Use
     * {@link #executeTool(Long, InvocationPrincipal, ToolModel.ToolExecutionRequest)}.
     */
    @Deprecated
    public ToolExecutionResult executeTool(Long chatbotId, String userId, ToolModel.ToolExecutionRequest request) {
        return executeTool(chatbotId, InvocationPrincipal.of(userId), request);
    }

    /**
     * Main entry point. The {@link InvocationPrincipal} is the authority the invocation is made on
     * behalf of and is required - there is no ambient-identity path.
     */
    public ToolExecutionResult executeTool(Long chatbotId, InvocationPrincipal principal,
                                           ToolModel.ToolExecutionRequest request) {

        long startTime = System.currentTimeMillis();

        // Create context with try-with-resources for automatic cleanup
        try (ExecutionContext context = contextFactory.create(chatbotId, principal)) {

            try {
                // Set MDC for logging
                MDC.put("executionId", context.getExecutionId());
                MDC.put("chatbotId", String.valueOf(chatbotId));
                MDC.put("userId", principal.getName());

                log.info("Starting tool execution: {} for principal: {}", request.getFuncNameKey(), principal);

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

        // Timed here rather than in executeTool() so nested eztool() calls are attributed to the
        // tool that actually ran, not just the outermost one.
        long startedAt = System.currentTimeMillis();
        String functionType = null;

        try {
            // Resolve the tool. A miss is not thrown here: it is handed to the policy so that
            // "unknown tool" is recorded as an explicit denial like every other refusal.
            ToolModel.Tool tool = null;
            try {
                tool = toolRegistry.getTool(context.getChatbotId(), toolId);
            } catch (ToolNotFoundException e) {
                log.debug("[executionId={}] Tool '{}' not found; deferring to policy",
                        context.getExecutionId(), toolId);
            }

            // ================= AUTHORITY GATE =================
            // Nothing below this point runs on the model's say-so. Applies to nested eztool()
            // calls too, because every nested call re-enters this method.
            PolicyDecision decision = policy.evaluate(
                    tool, toolId, context.getChatbotId(), context.getPrincipal(), request.getParams());

            metrics.recordPolicyDecision(toolId, decision.reason(), decision.allowed());

            if (!decision.allowed()) {
                log.warn("[executionId={}] POLICY DENY tool={} reason={} principal={} detail={}",
                        context.getExecutionId(), toolId, decision.reason(),
                        context.getPrincipal().getName(), decision.detail());
                throw new ToolAuthorizationException(
                        "Tool invocation denied: " + decision.detail(),
                        toolId, decision.reason(), context.getCallChainList());
            }

            log.info("[executionId={}] POLICY ALLOW tool={} sideEffect={} principal={}",
                    context.getExecutionId(), toolId, decision.sideEffect(),
                    context.getPrincipal().getName());
            // ==================================================

            functionType = tool.getFunctionType().name();

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

            metrics.recordToolExecution(toolId, functionType, AgentMetrics.OUTCOME_SUCCESS,
                    context.getChatbotId(), System.currentTimeMillis() - startedAt);

            return ToolExecutionResult.success(data);

        } catch (Exception e) {
            String errorCode = errorCodeOf(e);
            metrics.recordToolExecution(toolId, functionType,
                    (e instanceof ToolExecutionTimeoutException)
                            ? AgentMetrics.OUTCOME_TIMEOUT : AgentMetrics.OUTCOME_ERROR,
                    context.getChatbotId(), System.currentTimeMillis() - startedAt);
            metrics.recordToolError(toolId, errorCode);

            // Unregister with error
            context.unregisterToolCallWithError(toolId, e.getMessage());
            throw e;

        } finally {
            // CRITICAL: Always unregister on success
            if (!context.getCallChainList().isEmpty() &&
                    context.getCallChainList().get(context.getCallChainList().size() - 1).equals(toolId)) {
                // Only unregister if this tool is still on top of stack (not already unregistered due to error)
                // This is already handled by catch block above, so we don't double-unregister
                context.unregisterToolCall(toolId);
            }
        }
    }

    /**
     * Map an exception to a stable, low-cardinality metric tag.
     * Deliberately never uses the exception message - that would explode tag cardinality.
     */
    private String errorCodeOf(Exception e) {
        if (e instanceof ToolExecutionException te && te.getErrorCode() != null) {
            return te.getErrorCode();
        }
        if (e instanceof ToolAuthorizationException tae) return "TOOL_DENIED_" + tae.getReason();
        if (e instanceof SecurityException) return "GUARDRAIL_BLOCKED";
        if (e instanceof IllegalArgumentException) return "INVALID_PARAMETERS";
        return e.getClass().getSimpleName();
    }

    /**
     * Handle nested tool call from eztool()
     * This is called when a tool wants to call another tool
     *
     * @param context Current execution context
     * @param toolId  Tool to call
     * @param params  Parameters
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

    /**
     *  SQL execution
     */
    private Object executeSqlToolOriginal(ToolModel.Tool tool, Map<String, Object> params, Long chatbotId) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] SqlTool Originall: {} (Chat Id: {})", requestId, tool.getId(), chatbotId);
        try (Connection conn = dataSourceManager.getConnection(tool.getDataSource())) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(
                    new SingleConnectionDataSource(conn, true));

            String sql = tool.getSqlQuery();
            List<Object> paramValues = new ArrayList<>();

            /*
             Pattern explanation (Java string literal):
             - Matches any of:
               1) double-quoted template:   "{{$name}}"
               2) single-quoted template:   '{{$name}}'
               3) unquoted template:        {{$name}}
               4) colon-style parameter:    :name

             Capturing groups:
             group(1) => name inside double-quoted {{$...}}
             group(2) => name inside single-quoted {{$...}}
             group(3) => name inside unquoted {{$...}}
             group(4) => name for :name
             We'll pick the first non-null group to obtain the parameter name.
            */
            Pattern pattern = Pattern.compile(
                    "\"\\{\\{\\$([A-Za-z0-9_]+)\\}\\}\""        // "{{$name}}"
                            + "|'\\{\\{\\$([A-Za-z0-9_]+)\\}\\}'" // '{{$name}}'
                            + "|\\{\\{\\$([A-Za-z0-9_]+)\\}\\}"   // {{$name}}
                            + "|:([A-Za-z0-9_]+)"                 // :name
            );

            Matcher matcher = pattern.matcher(sql);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String paramName = null;
                if (matcher.group(1) != null) paramName = matcher.group(1);
                else if (matcher.group(2) != null) paramName = matcher.group(2);
                else if (matcher.group(3) != null) paramName = matcher.group(3);
                else if (matcher.group(4) != null) paramName = matcher.group(4);

                if (paramName == null) {
                    // Shouldn't happen, defensive check
                    throw new IllegalStateException("Matched parameter but could not determine name");
                }

                if (!params.containsKey(paramName)) {
                    throw new IllegalArgumentException("Missing parameter: " + paramName);
                }

                // Append replacement with a single JDBC placeholder.
                // Use Matcher.appendReplacement to preserve other parts of the SQL.
                matcher.appendReplacement(sb, "?");

                // Add the parameter value in the same order as placeholders.
                paramValues.add(params.get(paramName));
            }
            matcher.appendTail(sb);

            String finalSql = sb.toString();
            log.debug("[requestId={}] Final SQL: {}", requestId, finalSql);
            log.debug("[requestId={}] Param values: {}", requestId, paramValues);

            List<Map<String, Object>> result = jdbcTemplate.queryForList(
                    finalSql, paramValues.toArray());

            log.info("[requestId={}] SQL returned {} rows", requestId, result.size());
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * REST execution
     */
    private Object executeRestToolOriginal(ToolModel.Tool tool, Map<String, Object> params) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Rest Tool Original : {} (Params: {})", requestId, tool.getId(), params);

        String url = replacePlaceholders(tool.getHttpPath(), params);
        HttpHeaders headers = new HttpHeaders();
        if (tool.getHttpHeaders() != null) {
            RestHeaderPolicy headerPolicy =
                    new RestHeaderPolicy(config.getSecurity().getAllowedRequestHeaders());

            for (Map.Entry<String, String> entry : tool.getHttpHeaders().entrySet()) {
                String name = entry.getKey();
                if (!headerPolicy.isAllowed(name)) {
                    log.warn("[requestId={}] Dropping non-allowlisted request header '{}' on tool {}",
                            requestId, name, tool.getFuncNameKey());
                    continue;
                }

                String value = replacePlaceholders(entry.getValue(), params);
                headerPolicy.requireSafe(name, value);
                headers.add(name, value);
            }
        }

        String body = null;
        if (tool.getHttpBody() != null && !tool.getHttpBody().isEmpty()) {
            body = replacePlaceholders(tool.getHttpBody(), params);
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            log.info("[requestId={}] Rest Template URL: {} (Headers: {}) (Body: {})",
                    requestId, url, headers, body);

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

    /**
     *  parameter validation
     */
    private Map<String, Object> validateAndPrepareParameters(ToolModel.Tool tool, Map<String, Object> providedParams) {
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
     * placeholder
     */
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
}